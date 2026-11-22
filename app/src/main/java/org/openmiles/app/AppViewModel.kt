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
import org.openmiles.app.data.Odometer
import org.openmiles.app.data.Place
import org.openmiles.app.data.Rate
import org.openmiles.app.data.Rules
import org.openmiles.app.data.Seed
import org.openmiles.app.data.Source
import org.openmiles.app.data.Trip
import org.openmiles.app.data.WorkHoursStore
import org.openmiles.app.domain.AutoClassify
import org.openmiles.app.domain.BackupCategory
import org.openmiles.app.domain.BackupCodec
import org.openmiles.app.domain.BackupInfo
import org.openmiles.app.domain.BackupMerge
import org.openmiles.app.domain.BackupOdometer
import org.openmiles.app.domain.BackupPlace
import org.openmiles.app.domain.BackupRate
import org.openmiles.app.domain.BackupTrip
import org.openmiles.app.domain.Calc
import org.openmiles.app.domain.ImportedDrive
import org.openmiles.app.domain.MileIqDrive
import org.openmiles.app.domain.MileIqImport
import org.openmiles.app.domain.TimelineImport
import org.openmiles.app.domain.TripEdit
import org.openmiles.app.domain.Validation
import org.openmiles.app.domain.WorkHours
import org.openmiles.app.tracking.AutoDetect
import org.openmiles.app.tracking.Channels
import org.openmiles.app.tracking.Summary
import org.openmiles.app.tracking.DriveEndNotice
import org.openmiles.app.tracking.AutoClassifiedNotice
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
        Channels.ensure(app)
        Summary.schedule(app)
    }

    /** Swipe right or left: use the built-in category for that kind. */
    fun quickClassify(tripId: Long, kind: String) {
        viewModelScope.launch {
            val category = db.categoryDao().builtIn(kind) ?: return@launch
            db.tripDao().setCategory(tripId, category.id)
            clearNotices(tripId)
        }
    }

    fun setCategory(tripId: Long, categoryId: Long?) {
        viewModelScope.launch {
            db.tripDao().setCategory(tripId, categoryId)
            clearNotices(tripId)
        }
    }

    /** A drive classified in the app no longer needs its "Business or personal?" notification. */
    private fun clearNotices(tripId: Long) {
        val app = getApplication<Application>()
        DriveEndNotice.cancel(app, tripId)
        AutoClassifiedNotice.cancel(app, tripId)
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
                db.placeDao().clearCategory(category.id)
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
        if (endMs <= startMs || endMs - startMs > MAX_DRIVE_MS || !Validation.isValidManualMiles(miles)) return
        viewModelScope.launch {
            // No category picked: let the work-hours rule decide, if there is one.
            val chosen = categoryId ?: Rules.load(getApplication(), db).categoryFor(null, null, startMs)
            db.tripDao().insert(
                Trip(
                    startTime = startMs,
                    endTime = endMs,
                    distanceMeters = miles * Calc.METERS_PER_MILE,
                    categoryId = chosen,
                    notes = notes.trim().take(MAX_NOTES),
                    source = Source.MANUAL
                )
            )
        }
    }

    // ---- Places, work hours and auto-classify rules ----------------------------------------------

    val places = db.placeDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _work = MutableStateFlow(WorkHoursStore.load(app))
    val workHours: StateFlow<WorkHours> = _work

    private val _rulesMessage = MutableStateFlow<String?>(null)
    val rulesMessage: StateFlow<String?> = _rulesMessage

    fun saveWorkHours(work: WorkHours) {
        WorkHoursStore.save(getApplication(), work)
        _work.value = work
    }

    fun addPlace(name: String, lat: Double, lng: Double, categoryId: Long?, ignoreDrives: Boolean) {
        val clean = name.trim().take(MAX_CATEGORY_NAME)
        if (clean.isEmpty() || !lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return
        viewModelScope.launch {
            db.placeDao().insert(Place(name = clean, lat = lat, lng = lng, categoryId = categoryId, ignoreDrives = ignoreDrives))
        }
    }

    /** Names the start or end of a drive as a place. */
    fun addPlaceFromTrip(tripId: Long, useEnd: Boolean, name: String, categoryId: Long?) {
        viewModelScope.launch {
            val t = db.tripDao().get(tripId) ?: return@launch
            val ends = AutoClassify.ends(t.route)
            if (ends == null) {
                _rulesMessage.value = "That drive has no GPS route, so it has no place to name."
                return@launch
            }
            val p = if (useEnd) ends.second else ends.first
            addPlace(name, p.first, p.second, categoryId, false)
            _rulesMessage.value = "Saved \"${name.trim()}\" as a place."
        }
    }

    fun updatePlace(place: Place) {
        viewModelScope.launch { db.placeDao().update(place.copy(name = place.name.trim().take(MAX_CATEGORY_NAME))) }
    }

    fun deletePlace(place: Place) {
        viewModelScope.launch { db.placeDao().delete(place) }
    }

    /** Runs the rules over every drive that has no category yet. Never changes a chosen category. */
    fun applyRulesToUnclassified() {
        _rulesMessage.value = "Applying rules..."
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _rulesMessage.value = try {
                var changed = 0
                db.runInTransaction {
                    val rules = Rules.loadNow(app, db)
                    var after = 0L
                    while (true) {
                        val page = db.tripDao().unclassifiedAfterNow(after, BACKUP_PAGE)
                        if (page.isEmpty()) break
                        page.forEach { t ->
                            rules.categoryForRoute(t.route, t.startTime)?.let {
                                db.tripDao().setCategoryIfEmptyNow(t.id, it)
                                changed++
                            }
                        }
                        after = page.last().id
                    }
                }
                if (changed == 0) "No unclassified drives matched your rules." else "Classified $changed drives."
            } catch (e: Exception) {
                "The rules could not be applied. Nothing was changed."
            }
        }
    }

    fun clearRulesMessage() {
        _rulesMessage.value = null
    }

    // ---- Parking, tolls, odometer and report details ---------------------------------------------

    fun setExpenses(tripId: Long, parkingCents: Long, tollsCents: Long) {
        if (parkingCents !in 0..Validation.MAX_EXPENSE_CENTS || tollsCents !in 0..Validation.MAX_EXPENSE_CENTS) return
        viewModelScope.launch { db.tripDao().setExpenses(tripId, parkingCents, tollsCents) }
    }

    val odometer = db.odometerDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveOdometer(year: Int, startMiles: Double?, endMiles: Double?) {
        if (year !in 1990..2200) return
        if (startMiles != null && !Validation.isValidOdometer(startMiles)) return
        if (endMiles != null && !Validation.isValidOdometer(endMiles)) return
        viewModelScope.launch { db.odometerDao().save(Odometer(year, startMiles, endMiles)) }
    }

    private val reportPrefs = app.getSharedPreferences("report", Context.MODE_PRIVATE)
    private val _reportName = MutableStateFlow(reportPrefs.getString("name", "") ?: "")
    val reportName: StateFlow<String> = _reportName
    private val _reportVehicle = MutableStateFlow(reportPrefs.getString("vehicle", "") ?: "")
    val reportVehicle: StateFlow<String> = _reportVehicle

    fun saveReportDetails(name: String, vehicle: String) {
        val n = name.trim().take(60)
        val v = vehicle.trim().take(60)
        reportPrefs.edit().putString("name", n).putString("vehicle", v).apply()
        _reportName.value = n
        _reportVehicle.value = v
    }

    // ---- Editing, splitting and merging drives -------------------------------------------------

    /** The saved GPS route of one drive, for its map. Empty when it has none. */
    suspend fun routeOf(id: Long): String = db.tripDao().get(id)?.route ?: ""

    /** Changes a drive's times and miles. The drive is marked as edited in reports. */
    fun editTrip(id: Long, startMs: Long, endMs: Long, miles: Double) {
        if (endMs <= startMs || endMs - startMs > MAX_DRIVE_MS || !Validation.isValidManualMiles(miles)) return
        viewModelScope.launch {
            val t = db.tripDao().get(id) ?: return@launch
            db.tripDao().update(
                t.copy(startTime = startMs, endTime = endMs, distanceMeters = miles * Calc.METERS_PER_MILE, edited = true)
            )
        }
    }

    /** Splits one drive into two at [atMs], dividing the miles along the GPS route when there is one. */
    fun splitTrip(id: Long, atMs: Long) {
        viewModelScope.launch {
            db.withTransaction {
                val t = db.tripDao().get(id) ?: return@withTransaction
                val parts = TripEdit.split(t.startTime, t.endTime, t.distanceMeters, t.route, atMs)
                    ?: return@withTransaction
                db.tripDao().update(
                    t.copy(endTime = atMs, distanceMeters = parts.firstMeters, route = parts.firstRoute, edited = true)
                )
                db.tripDao().insert(
                    t.copy(id = 0, startTime = atMs, distanceMeters = parts.secondMeters, route = parts.secondRoute, edited = true)
                )
            }
        }
    }

    /** Joins a drive with the one after it, for example when a stop split one trip in two. */
    fun mergeTrips(firstId: Long, secondId: Long) {
        if (firstId == secondId) return
        viewModelScope.launch {
            db.withTransaction {
                val a0 = db.tripDao().get(firstId) ?: return@withTransaction
                val b0 = db.tripDao().get(secondId) ?: return@withTransaction
                val (a, b) = if (a0.startTime <= b0.startTime) a0 to b0 else b0 to a0
                db.tripDao().update(
                    a.copy(
                        endTime = maxOf(a.endTime, b.endTime),
                        distanceMeters = a.distanceMeters + b.distanceMeters,
                        categoryId = a.categoryId ?: b.categoryId,
                        notes = TripEdit.mergeNotes(a.notes, b.notes, MAX_NOTES),
                        route = TripEdit.mergeRoutes(a.route, b.route),
                        source = if (a.source == b.source) a.source else Source.MANUAL,
                        edited = true
                    )
                )
                db.tripDao().deleteById(b.id)
            }
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
                val rules = Rules.load(getApplication(), db)
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
                                    source = Source.TIMELINE,
                                    categoryId = rules.categoryFor(
                                        it.startLat to it.startLng, it.endLat to it.endLng, it.startMs
                                    )
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

    // ---- MileIQ import ---------------------------------------------------------------------

    sealed class MileIqState {
        object Idle : MileIqState()
        object Reading : MileIqState()
        data class Ready(val parsed: MileIqImport.Parsed, val overlapping: BooleanArray) : MileIqState()
        object Saving : MileIqState()
        data class Done(val added: Int) : MileIqState()
        data class Failed(val message: String) : MileIqState()
    }

    private val _mileIq = MutableStateFlow<MileIqState>(MileIqState.Idle)
    val mileIqState: StateFlow<MileIqState> = _mileIq

    /** Reads a MileIQ CSV report on a background thread. Nothing is saved until the person confirms. */
    fun readMileIq(uri: Uri) {
        _mileIq.value = MileIqState.Reading
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _mileIq.value = try {
                val stream = app.contentResolver.openInputStream(uri)
                    ?: throw MileIqImport.NotAMileIqFile("Could not open that file.")
                val parsed = stream.use { MileIqImport.parse(InputStreamReader(it, Charsets.UTF_8).buffered()) }
                if (parsed.drives.isEmpty()) {
                    MileIqState.Failed("No drives were found in that file.")
                } else {
                    MileIqState.Ready(parsed, MileIqImport.overlaps(parsed.drives, db.tripDao().allSpans()))
                }
            } catch (e: MileIqImport.NotAMileIqFile) {
                MileIqState.Failed(e.message ?: "That file could not be read.")
            } catch (e: Exception) {
                MileIqState.Failed("That file could not be read. Make sure it is a CSV report from MileIQ.")
            } catch (e: OutOfMemoryError) {
                MileIqState.Failed("That file is too large for this phone to read.")
            }
        }
    }

    /** Saves the chosen MileIQ drives, all at once or not at all. MileIQ's categories are kept. */
    fun importMileIq(drives: List<MileIqDrive>) {
        _mileIq.value = MileIqState.Saving
        viewModelScope.launch(Dispatchers.IO) {
            _mileIq.value = try {
                val categories = db.categoryDao().all()
                fun categoryFor(d: MileIqDrive): Long? {
                    val kind = d.kind ?: return null
                    // A category with the same name as MileIQ's purpose wins, then the built-in one.
                    return categories.firstOrNull { it.kind == kind && it.name.equals(d.purpose, ignoreCase = true) }?.id
                        ?: categories.firstOrNull { it.kind == kind && it.builtIn }?.id
                        ?: categories.firstOrNull { it.kind == kind }?.id
                }
                db.withTransaction {
                    drives.chunked(500).forEach { chunk ->
                        db.tripDao().insertAll(
                            chunk.map {
                                Trip(
                                    startTime = it.startMs,
                                    endTime = it.endMs,
                                    distanceMeters = it.meters,
                                    categoryId = categoryFor(it),
                                    notes = it.noteText(MAX_NOTES),
                                    source = Source.MILEIQ,
                                    parkingCents = it.parkingCents,
                                    tollsCents = it.tollsCents
                                )
                            }
                        )
                    }
                }
                MileIqState.Done(drives.size)
            } catch (e: Exception) {
                MileIqState.Failed("The drives could not be saved. Nothing was added.")
            }
        }
    }

    fun clearMileIq() {
        _mileIq.value = MileIqState.Idle
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
            val newRates: Int,
            val newPlaces: Int
        ) : BackupState()
        data class Restored(val drives: Int, val categories: Int, val rates: Int, val places: Int) : BackupState()
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
                val places = db.placeDao().all().map {
                    BackupPlace(it.name, it.lat, it.lng, it.radiusMeters, it.categoryId, it.ignoreDrives)
                }
                val work = WorkHoursStore.load(app)
                val readings = db.odometerDao().all().map { BackupOdometer(it.year, it.startMiles, it.endMiles) }
                val expected = db.tripDao().count()
                val out = app.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("no stream")
                val written = out.use { stream ->
                    val writer = OutputStreamWriter(stream, Charsets.UTF_8).buffered()
                    val n = BackupCodec.write(writer, BuildConfig.VERSION_NAME, System.currentTimeMillis(), categories, rates, places, work, readings) { emit ->
                        var offset = 0
                        while (true) {
                            val page = db.tripDao().pageNow(BACKUP_PAGE, offset)
                            if (page.isEmpty()) break
                            page.forEach {
                                emit(
                                    BackupTrip(
                                        it.startTime, it.endTime, it.distanceMeters, it.categoryId, it.notes,
                                        it.route, it.source, it.edited, it.parkingCents, it.tollsCents
                                    )
                                )
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
                val placeKeys = db.placeDao().all().map { BackupMerge.placeKey(it.name, it.lat, it.lng) }.toSet()
                BackupState.RestoreReady(
                    uri, info, newDrives, already,
                    BackupMerge.missingCategories(info.categories, catKeys).size,
                    BackupMerge.missingRates(info.rates, rateKeys).size,
                    info.places.map { BackupMerge.placeKey(it.name, it.lat, it.lng) }.distinct().count { it !in placeKeys }
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
                var addedPlaces = 0
                var restoredWork: WorkHours? = null
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
                    // Places: add the ones this phone does not have, with their rules.
                    val placeKeys = db.placeDao().allNow().map { BackupMerge.placeKey(it.name, it.lat, it.lng) }.toMutableSet()
                    ready.info.places.forEach { p ->
                        if (placeKeys.add(BackupMerge.placeKey(p.name, p.lat, p.lng))) {
                            db.placeDao().insertNow(
                                Place(
                                    name = p.name, lat = p.lat, lng = p.lng, radiusMeters = p.radiusMeters,
                                    categoryId = p.categoryId?.let { idMap[it] }, ignoreDrives = p.ignoreDrives
                                )
                            )
                            addedPlaces++
                        }
                    }
                    // Odometer readings: only for years this phone has none.
                    ready.info.odometer.forEach {
                        db.odometerDao().insertIfMissingNow(Odometer(it.year, it.startMiles, it.endMiles))
                    }
                    // Work hours: only if this phone has never set them.
                    val w = ready.info.workHours
                    if (w != null && !WorkHoursStore.isSet(app)) {
                        restoredWork = w.copy(categoryId = w.categoryId?.let { idMap[it] })
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
                                    source = t.source,
                                    edited = t.edited,
                                    parkingCents = t.parkingCents,
                                    tollsCents = t.tollsCents
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
                // Saved only after the transaction succeeded.
                restoredWork?.let { saveWorkHours(it) }
                BackupState.Restored(added, addedCats, addedRates, addedPlaces)
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
        const val MAX_DRIVE_MS = 24 * 3_600_000L
        const val MAX_NOTES = 200
        const val MAX_CATEGORY_NAME = 40
    }
}
