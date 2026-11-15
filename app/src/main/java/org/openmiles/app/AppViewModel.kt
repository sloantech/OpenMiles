package org.openmiles.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openmiles.app.data.AppDatabase
import org.openmiles.app.data.Category
import org.openmiles.app.data.Rate
import org.openmiles.app.data.Seed
import org.openmiles.app.data.Trip
import org.openmiles.app.tracking.LiveTrip
import org.openmiles.app.tracking.TrackingState

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)

    val trips = db.tripDao().observeAll()
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
        viewModelScope.launch { db.tripDao().setNotes(tripId, notes) }
    }

    fun deleteTrip(trip: Trip) {
        viewModelScope.launch { db.tripDao().delete(trip) }
    }

    fun addCategory(name: String, kind: String) {
        viewModelScope.launch {
            db.categoryDao().insert(Category(name = name.trim(), kind = kind))
        }
    }

    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            db.tripDao().clearCategory(category.id)
            db.categoryDao().delete(category)
        }
    }

    fun addRate(kind: String, effectiveFrom: String, dollarsPerMile: Double) {
        viewModelScope.launch {
            db.rateDao().insert(
                Rate(kind = kind, effectiveFrom = effectiveFrom, dollarsPerMile = dollarsPerMile)
            )
        }
    }

    fun deleteRate(rate: Rate) {
        viewModelScope.launch { db.rateDao().delete(rate) }
    }
}
