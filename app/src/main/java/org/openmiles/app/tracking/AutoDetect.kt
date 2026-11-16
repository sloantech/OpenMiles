package org.openmiles.app.tracking

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.openmiles.app.data.AppDatabase
import org.openmiles.app.data.Trip
import org.openmiles.app.domain.Draft
import org.openmiles.app.domain.TripRecorder
import java.io.File

/**
 * Optional automatic drive detection. It is off until the person turns it on in Settings.
 *
 * Two signals start a drive:
 *  - Android's activity sensor says the phone is now in a vehicle.
 *  - The phone leaves an invisible 150 m circle around where the car was last parked.
 * Android's activity sensor saying the phone left the vehicle ends a drive, and so do the rules
 * in AutoTripMonitor (parked for 5 minutes, or never fast enough to be a drive).
 */
object AutoDetect {
    private const val PREFS = "auto_detect"
    private const val KEY_ON = "on"
    private const val KEY_PARK_LAT = "park_lat"
    private const val KEY_PARK_LNG = "park_lng"
    private const val KEY_PARK_TIME = "park_time"
    private const val FENCE_ID = "parked"
    private const val FENCE_RADIUS_M = 150f

    const val ACTION_TRANSITION = "org.openmiles.app.ACTIVITY_TRANSITION"
    const val ACTION_GEOFENCE = "org.openmiles.app.GEOFENCE"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Plain names of the permissions auto-detect still needs. Empty means all set. */
    fun missingPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (!granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) missing += "Precise location"
        if (Build.VERSION.SDK_INT >= 29) {
            if (!granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                missing += "Location: Allow all the time"
            }
            if (!granted(context, Manifest.permission.ACTIVITY_RECOGNITION)) missing += "Physical activity"
        }
        return missing
    }

    fun hasAllPermissions(context: Context): Boolean = missingPermissions(context).isEmpty()

    /** Turns detection on. Returns false if a permission is still missing. */
    fun enable(context: Context): Boolean {
        if (!hasAllPermissions(context)) return false
        prefs(context).edit().putBoolean(KEY_ON, true).apply()
        register(context)
        return true
    }

    /** Turns detection off and removes every background signal. */
    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ON, false).apply()
        try {
            ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(pendingIntent(context, ACTION_TRANSITION))
        } catch (e: SecurityException) {
            // The permission is already gone, so Android has stopped the updates.
        } catch (e: Exception) {
            // Nothing was registered.
        }
        try {
            LocationServices.getGeofencingClient(context).removeGeofences(listOf(FENCE_ID))
        } catch (e: Exception) {
            // Nothing was registered.
        }
    }

    /**
     * Signs up for the background signals. Safe to call often: each call replaces the last.
     * Android forgets these after a restart, so the boot receiver calls this again.
     */
    fun register(context: Context) {
        if (!isEnabled(context) || !hasAllPermissions(context)) return
        val transitions = listOf(
            ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            ActivityTransition.ACTIVITY_TRANSITION_EXIT
        ).map {
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(it)
                .build()
        }
        try {
            ActivityRecognition.getClient(context).requestActivityTransitionUpdates(
                ActivityTransitionRequest(transitions),
                pendingIntent(context, ACTION_TRANSITION)
            )
        } catch (e: SecurityException) {
            // A permission was taken away. Settings shows what is missing.
        }
        parkedAt(context)?.let { setFence(context, it) }
    }

    /** Remembers where a drive ended and, if detection is on, watches for leaving that spot. */
    fun onParked(context: Context, fix: TripRecorder.Fix?) {
        if (fix == null) return
        prefs(context).edit()
            .putLong(KEY_PARK_LAT, java.lang.Double.doubleToRawLongBits(fix.lat))
            .putLong(KEY_PARK_LNG, java.lang.Double.doubleToRawLongBits(fix.lng))
            .putLong(KEY_PARK_TIME, fix.timeMs)
            .apply()
        if (isEnabled(context) && hasAllPermissions(context)) setFence(context, fix)
    }

    /** Drops the saved parking spot and its fence, when it can no longer be trusted. */
    fun forgetParking(context: Context) {
        prefs(context).edit()
            .remove(KEY_PARK_LAT).remove(KEY_PARK_LNG).remove(KEY_PARK_TIME)
            .apply()
        try {
            LocationServices.getGeofencingClient(context).removeGeofences(listOf(FENCE_ID))
        } catch (e: SecurityException) {
            // The permission is already gone, so Android has removed the fence.
        } catch (e: Exception) {
            // Nothing was registered.
        }
    }

    fun parkedAt(context: Context): TripRecorder.Fix? {
        val p = prefs(context)
        if (!p.contains(KEY_PARK_TIME)) return null
        val lat = java.lang.Double.longBitsToDouble(p.getLong(KEY_PARK_LAT, 0L))
        val lng = java.lang.Double.longBitsToDouble(p.getLong(KEY_PARK_LNG, 0L))
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return TripRecorder.Fix(lat, lng, p.getLong(KEY_PARK_TIME, 0L), null)
    }

    private fun setFence(context: Context, at: TripRecorder.Fix) {
        val fence = Geofence.Builder()
            .setRequestId(FENCE_ID)
            .setCircularRegion(at.lat, at.lng, FENCE_RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()
        try {
            LocationServices.getGeofencingClient(context)
                .addGeofences(request, pendingIntent(context, ACTION_GEOFENCE))
        } catch (e: SecurityException) {
            // A permission was taken away. Settings shows what is missing.
        }
    }

    private fun pendingIntent(context: Context, action: String): PendingIntent {
        // Google Play services adds the event details, so this one has to be mutable.
        // The intent names our own receiver, so no other app can receive it.
        val intent = Intent(context, DetectionReceiver::class.java).setAction(action)
        val code = if (action == ACTION_TRANSITION) 10 else 11
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}

/** Receives drive signals from Google Play services. Not reachable by other apps. */
class DetectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AutoDetect.isEnabled(context)) return
        when (intent.action) {
            AutoDetect.ACTION_TRANSITION -> {
                if (!ActivityTransitionResult.hasResult(intent)) return
                val result = ActivityTransitionResult.extractResult(intent) ?: return
                val newest = result.transitionEvents
                    .filter { it.activityType == DetectedActivity.IN_VEHICLE }
                    .maxByOrNull { it.elapsedRealTimeNanos } ?: return
                // Ignore signals that arrive very late.
                val ageMs = (SystemClock.elapsedRealtimeNanos() - newest.elapsedRealTimeNanos) / 1_000_000
                if (ageMs > MAX_SIGNAL_AGE_MS) return
                if (newest.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    startIfIdle(context)
                } else if (TrackingState.isAutoActive()) {
                    TrackingService.autoStop(context)
                }
            }
            AutoDetect.ACTION_GEOFENCE -> {
                val event = GeofencingEvent.fromIntent(intent) ?: return
                if (event.hasError()) return
                if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) startIfIdle(context)
            }
        }
    }

    private fun startIfIdle(context: Context) {
        if (TrackingState.live.value.active) return
        if (!AutoDetect.hasAllPermissions(context)) return
        TrackingService.autoStart(context)
    }

    private companion object {
        const val MAX_SIGNAL_AGE_MS = 10 * 60_000L
    }
}

/** Android forgets detection after a restart or an app update. This signs up again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Only the system can send these two, so any other message is ignored.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            AutoDetect.register(context)
        }
    }
}

/** Keeps the drive in progress in a small private file, so a crash cannot lose it. */
object DraftStore {
    private fun file(context: Context) = File(context.filesDir, "drive-in-progress.txt")

    @Synchronized
    fun write(context: Context, draft: Draft) {
        val target = file(context)
        val temp = File(target.path + ".tmp")
        temp.writeText(draft.encode())
        // A rename is all-or-nothing, so the saved file is never half written.
        if (!temp.renameTo(target)) temp.delete()
    }

    /** Reads and removes a saved draft, if there is one. */
    @Synchronized
    fun take(context: Context): Draft? {
        val f = file(context)
        if (!f.exists()) return null
        val draft = runCatching { Draft.decode(f.readText()) }.getOrNull()
        f.delete()
        return draft
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    /** Saves a recovered drive, unless it is too short or was already saved. */
    suspend fun saveRecovered(context: Context, draft: Draft) {
        if (!draft.worthKeeping()) return
        val dao = AppDatabase.get(context).tripDao()
        if (dao.countStartingAt(draft.startMs) > 0) return
        dao.insert(
            Trip(
                startTime = draft.startMs,
                endTime = draft.endMs,
                distanceMeters = draft.meters,
                route = draft.route
            )
        )
    }
}
