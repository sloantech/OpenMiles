package org.openmiles.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
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
import org.openmiles.app.domain.Calc
import org.openmiles.app.domain.ImportedDrive
import org.openmiles.app.domain.TimelineImport
import org.openmiles.app.domain.Validation
import org.openmiles.app.tracking.AutoDetect
import org.openmiles.app.tracking.DraftStore
import org.openmiles.app.tracking.LiveTrip
import org.openmiles.app.tracking.TrackingState
import java.io.InputStreamReader
import java.util.Locale

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

    companion object {
        const val MAX_NOTES = 200
        const val MAX_CATEGORY_NAME = 40
    }
}
