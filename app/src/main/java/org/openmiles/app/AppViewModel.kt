package org.openmiles.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openmiles.app.data.AppDatabase
import org.openmiles.app.data.Category
import org.openmiles.app.data.Kind
import org.openmiles.app.data.Rate
import org.openmiles.app.data.Seed
import org.openmiles.app.data.Source
import org.openmiles.app.data.Trip
import org.openmiles.app.domain.BackupCategory
import org.openmiles.app.domain.BackupCodec
import org.openmiles.app.domain.BackupInfo
import org.openmiles.app.domain.BackupMerge
import org.openmiles.app.domain.BackupRate
import org.openmiles.app.domain.BackupTrip
import org.openmiles.app.domain.Calc
import org.openmiles.app.domain.ImportedDrive
import org.openmiles.app.domain.TimelineImport
import org.openmiles.app.domain.Validation
import org.openmiles.app.tracking.AutoDetect
import org.openmiles.app.tracking.DraftStore
import org.openmiles.app.tracking.LiveTrip
import org.openmiles.app.tracking.TrackingState

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)

    val trips = db.tripDao().observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = db.categoryDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rates = db.rateDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val live: StateFlow<LiveTrip> = TrackingState.live

    init {
        viewModelScope.launch(Dispatchers.IO) {
            Seed.run(db)
            // Save a drive that was cut off when Android closed the app.
            if (!TrackingState.live.value.active) {
                DraftStore.take(app)?.let { DraftStore.saveRecovered(app, it) }
            }
        }
        // Detection can be forgotten (for example after a phone update). Sign up again.
        AutoDetect.register(app)
    }

    /** Swipe right or left: use the built-in category for that kind. */
    fun quickClassify(tripId: Long, kind: String) {
        viewModelScope.launch {
            val category = db.categoryDao().builtIn(kind) ?: return@launch
            db.tripDao().setCategory(tripId, category.id)
        }
    }

    fun setCategory(tripId: Long, categoryId: Long?) {
        viewModelScope.launch { db.tripDao().setCategory(tripId, categoryId) }
    }

    fun setNotes(tripId: Long, notes: String) {
        viewModelScope.launch { db.tripDao().setNotes(tripId, notes.take(MAX_NOTES)) }
    }

    fun deleteTrip(tripId: Long) {
        viewModelScope.launch { db.tripDao().deleteById(tripId) }
    }

    fun addCategory(name: String, kind: String) {
        val clean = name.trim().take(MAX_CATEGORY_NAME)
        if (clean.isEmpty()) return
        viewModelScope.launch {
            db.categoryDao().insert(Category(name = clean, kind = kind))
        }
    }

    fun deleteCategory(category: Category) {
        // Built-in categories can never be removed.
        if (category.builtIn) return
        viewModelScope.launch {
            // Both steps succeed together or not at all.
            db.withTransaction {
                db.tripDao().clearCategory(category.id)
                db.categoryDao().delete(category)
            }
        }
    }

    fun addRate(kind: String, effectiveFrom: String, dollarsPerMile: Double) {
        // The screen checks these too, but the data layer never trusts the screen.
        val day = Validation.parseDay(effectiveFrom) ?: return
        if (!Validation.isValidRate(dollarsPerMile)) return
        if (kind != Kind.BUSINESS && kind != Kind.MEDICAL && kind != Kind.CHARITY) return
        val startDate = day.toString()
        viewModelScope.launch {
            db.withTransaction {
                // Replace any rate that starts on the same date for the same kind.
                db.rateDao().deleteFor(kind, startDate)
                db.rateDao().insert(
                    Rate(kind = kind, effectiveFrom = startDate, dollarsPerMile = dollarsPerMile)
                )
            }
        }
    }

    fun deleteRate(rate: Rate) {
        viewModelScope.launch { db.rateDao().delete(rate) }
    }

    /** A drive typed in by hand, for when the app was off. Marked so reports can tell. */
    fun addManualTrip(startMs: Long, endMs: Long, miles: Double, categoryId: Long?, notes: String) {
        if (endMs <= startMs || !Validation.isValidManualMiles(miles)) return
        viewModelScope.launch {
            db.tripDao().insert(
                Trip(
                    startTime = startMs,
                    endTime = endMs,
                    distanceMeters = miles * Calc.METERS_PER_MILE,
                    categoryId = categoryId,
                    notes = notes.trim().take(MAX_NOTES),
                    source = Source.MANUAL
                )
            )
        }
    }

    // ---- Google Timeline import ------------------------------------------------------------

    sealed class ImportState {
        object Idle : ImportState()
        object Reading : ImportState()
        data class Ready(
            val parsed: TimelineImport.Parsed,
            val overlapping: BooleanArray
        ) : ImportState()
        object Saving : ImportState()
        data class Done(val added: Int) : ImportState()
        data class Failed(val message: String) : ImportState()
    }

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _import

    /** Reads the chosen file on a background thread. Nothing is saved until the person confirms. */
    fun readTimeline(uri: Uri) {
        _import.value = ImportState.Reading
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _import.value = try {
                val stream = app.contentResolver.openInputStream(uri)
                    ?: throw TimelineImport.NotATimelineFile("Could not open that file.")
                val parsed = stream.use { TimelineImport.parse(InputStreamReader(it, Charsets.UTF_8).buffered()) }
                if (parsed.drives.isEmpty()) {
                    ImportState.Failed("No drives were found in that file.")
                } else {
                    ImportState.Ready(parsed, TimelineImport.overlaps(parsed.drives, db.tripDao().allSpans()))
                }
            } catch (e: TimelineImport.NotATimelineFile) {
                ImportState.Failed(e.message ?: "That file could not be read.")
            } catch (e: Exception) {
                ImportState.Failed("That file could not be read. Make sure it is the Timeline export from your phone.")
            } catch (e: OutOfMemoryError) {
                ImportState.Failed("That file is too large for this phone to read.")
            }
        }
    }

    /** Saves the drives chosen on the preview screen, all at once or not at all. */
    fun importDrives(drives: List<ImportedDrive>) {
        _import.value = ImportState.Saving
        viewModelScope.launch(Dispatchers.IO) {
            _import.value = try {
                db.withTransaction {
                    drives.chunked(500).forEach { chunk ->
                        db.tripDao().insertAll(
                            chunk.map {
                                Trip(
                                    startTime = it.startMs,
                                    endTime = it.endMs,
                                    distanceMeters = it.meters,
                                    route = String.format(
                                        Locale.US, "%.5f,%.5f,%d;%.5f,%.5f,%d",
                                        it.startLat, it.startLng, it.startMs, it.endLat, it.endLng, it.endMs
                                    ),
                                    source = Source.TIMELINE
                                )
                            }
                        )
                    }
                }
                ImportState.Done(drives.size)
            } catch (e: Exception) {
                ImportState.Failed("The drives could not be saved. Nothing was added.")
            }
        }
    }

    fun clearImport() {
        _import.value = ImportState.Idle
    }

    // ---- Backup and restore ------------------------------------------------------------------

    sealed class BackupState {
        object Idle : BackupState()
        object Working : BackupState()
        data class Saved(val drives: Int) : BackupState()
        data class RestoreReady(
            val uri: Uri,
            val info: BackupInfo,
            val newDrives: Int,
            val alreadyHere: Int,
            val newCategories: Int,
            val newRates: Int
        ) : BackupState()
        data class Restored(val drives: Int, val categories: Int, val rates: Int) : BackupState()
        data class Failed(val message: String) : BackupState()
    }

    private val _backup = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backup

    private val backupPrefs = app.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val _lastBackup = MutableStateFlow(backupPrefs.getLong("last", 0L))
    val lastBackup: StateFlow<Long> = _lastBackup

    /** Writes a backup to the file the person chose, then reads it back to make sure it is whole. */
    fun saveBackup(uri: Uri) {
        _backup.value = BackupState.Working
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _backup.value = try {
                val categories = db.categoryDao().all().map { BackupCategory(it.id, it.name, it.kind, it.builtIn) }
                val rates = db.rateDao().all().map { BackupRate(it.kind, it.effectiveFrom, it.dollarsPerMile) }
                val expected = db.tripDao().count()
                val out = app.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("no stream")
                val written = out.use { stream ->
                    val writer = OutputStreamWriter(stream, Charsets.UTF_8).buffered()
                    val n = BackupCodec.write(writer, BuildConfig.VERSION_NAME, System.currentTimeMillis(), categories, rates) { emit ->
                        var offset = 0
                        while (true) {
                            val page = db.tripDao().pageNow(BACKUP_PAGE, offset)
                            if (page.isEmpty()) break
                            page.forEach {
                                emit(BackupTrip(it.startTime, it.endTime, it.distanceMeters, it.categoryId, it.notes, it.route, it.source))
                            }
                            offset += page.size
                        }
                    }
                    writer.flush()
                    n
                }
                // Check the saved file before calling it a success.
                val check = app.contentResolver.openInputStream(uri)?.use { stream ->
                    BackupCodec.read(InputStreamReader(stream, Charsets.UTF_8).buffered()) { }
                }
                if (check == null || check.trips != written || written < expected) {
                    BackupState.Failed("The backup could not be checked after saving. Please try again, or pick another place to save it.")
                } else {
                    val now = System.currentTimeMillis()
                    backupPrefs.edit().putLong("last", now).apply()
                    _lastBackup.value = now
                    BackupState.Saved(written)
                }
            } catch (e: Exception) {
                BackupState.Failed("The backup could not be saved. Please try again, or pick another place to save it.")
            } catch (e: OutOfMemoryError) {
                BackupState.Failed("The backup could not be saved. Please try again.")
            }
        }
    }

    /** First look at a backup: what would be added. Nothing is changed yet. */
    fun readBackup(uri: Uri) {
        _backup.value = BackupState.Working
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _backup.value = try {
                val here = db.tripDao().allSpans().map { BackupMerge.spanKey(it.startTime, it.endTime) }.toHashSet()
                val seen = HashSet<Pair<Long, Long>>()
                var newDrives = 0
                var already = 0
                val info = app.contentResolver.openInputStream(uri)?.use { stream ->
                    BackupCodec.read(InputStreamReader(stream, Charsets.UTF_8).buffered()) { t ->
                        val k = BackupMerge.spanKey(t.startTime, t.endTime)
                        if (k in here || !seen.add(k)) already++ else newDrives++
                    }
                } ?: throw BackupCodec.NotABackup("Could not open that file.")
                val catKeys = db.categoryDao().all().map { BackupMerge.key(it.name, it.kind) }.toSet()
                val rateKeys = db.rateDao().all().map { it.kind to it.effectiveFrom }.toSet()
                BackupState.RestoreReady(
                    uri, info, newDrives, already,
                    BackupMerge.missingCategories(info.categories, catKeys).size,
                    BackupMerge.missingRates(info.rates, rateKeys).size
                )
            } catch (e: BackupCodec.NotABackup) {
                BackupState.Failed(e.message ?: "That file is not an OpenMiles backup.")
            } catch (e: Exception) {
                BackupState.Failed("That file could not be read. Make sure it is an OpenMiles backup.")
            } catch (e: OutOfMemoryError) {
                BackupState.Failed("That file is too large for this phone to read.")
            }
        }
    }

    /** Adds what is missing from the backup, all at once or not at all. */
    fun restoreBackup(ready: BackupState.RestoreReady) {
        _backup.value = BackupState.Working
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _backup.value = try {
                var added = 0
                var addedCats = 0
                var addedRates = 0
                // One plain transaction on this background thread: everything is added, or
                // nothing is if anything goes wrong.
                db.runInTransaction {
                    // Categories: match by name and kind; create the missing ones.
                    val existing = db.categoryDao().allNow()
                    val byKey = existing.associateBy { BackupMerge.key(it.name, it.kind) }.toMutableMap()
                    val idMap = HashMap<Long, Long>()
                    ready.info.categories.forEach { bc ->
                        val k = BackupMerge.key(bc.name, bc.kind)
                        val found = byKey[k]
                        val id = if (found != null) {
                            found.id
                        } else {
                            addedCats++
                            val newId = db.categoryDao().insertNow(Category(name = bc.name, kind = bc.kind, builtIn = false))
                            byKey[k] = Category(newId, bc.name, bc.kind, false)
                            newId
                        }
                        idMap[bc.id] = id
                    }
                    // Rates: add the ones this phone does not have.
                    val rateKeys = db.rateDao().allNow().map { it.kind to it.effectiveFrom }.toSet()
                    val newRates = BackupMerge.missingRates(ready.info.rates, rateKeys)
                    if (newRates.isNotEmpty()) {
                        db.rateDao().insertAllNow(
                            newRates.map { Rate(kind = it.kind, effectiveFrom = it.effectiveFrom, dollarsPerMile = it.dollarsPerMile) }
                        )
                        addedRates = newRates.size
                    }
                    // Drives: second pass over the file, adding only ones not already here.
                    val here = db.tripDao().allSpansNow().map { BackupMerge.spanKey(it.startTime, it.endTime) }.toHashSet()
                    val batch = ArrayList<Trip>(BACKUP_PAGE)
                    val stream = app.contentResolver.openInputStream(ready.uri)
                        ?: throw IllegalStateException("no stream")
                    stream.use {
                        BackupCodec.read(InputStreamReader(it, Charsets.UTF_8).buffered()) { t ->
                            if (here.add(BackupMerge.spanKey(t.startTime, t.endTime))) {
                                batch += Trip(
                                    startTime = t.startTime,
                                    endTime = t.endTime,
                                    distanceMeters = t.distanceMeters,
                                    categoryId = t.categoryId?.let { id -> idMap[id] },
                                    notes = t.notes,
                                    route = t.route,
                                    source = t.source
                                )
                                if (batch.size >= BACKUP_PAGE) {
                                    db.tripDao().insertAllNow(batch.toList())
                                    added += batch.size
                                    batch.clear()
                                }
                            }
                        }
                    }
                    if (batch.isNotEmpty()) {
                        db.tripDao().insertAllNow(batch.toList())
                        added += batch.size
                    }
                }
                BackupState.Restored(added, addedCats, addedRates)
            } catch (e: Exception) {
                BackupState.Failed("The restore did not finish, so nothing was changed. Please try again.")
            } catch (e: OutOfMemoryError) {
                BackupState.Failed("The restore did not finish, so nothing was changed.")
            }
        }
    }

    fun clearBackupState() {
        _backup.value = BackupState.Idle
    }

    companion object {
        const val BACKUP_PAGE = 200
        const val MAX_NOTES = 200
        const val MAX_CATEGORY_NAME = 40
    }
}
