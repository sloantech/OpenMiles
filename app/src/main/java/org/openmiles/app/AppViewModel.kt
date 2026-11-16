package org.openmiles.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openmiles.app.data.AppDatabase
import org.openmiles.app.data.Category
import org.openmiles.app.data.Kind
import org.openmiles.app.data.Rate
import org.openmiles.app.data.Seed
import org.openmiles.app.domain.Validation
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
        viewModelScope.launch { Seed.run(db) }
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

    companion object {
        const val MAX_NOTES = 200
        const val MAX_CATEGORY_NAME = 40
    }
}
