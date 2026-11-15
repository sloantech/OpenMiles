package org.openmiles.app.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.openmiles.app.MainActivity
import org.openmiles.app.R
import org.openmiles.app.data.AppDatabase
import org.openmiles.app.data.Trip
import java.util.Locale

/** What the screen shows while a drive is being recorded. */
data class LiveTrip(
    val active: Boolean = false,
    val startTime: Long = 0L,
    val meters: Double = 0.0,
    val points: Int = 0
)

/** A finished drive, ready to save. */
data class FinishedTrip(
    val startTime: Long,
    val endTime: Long,
    val meters: Double,
    val route: String
)

object TrackingState {
    private val _live = MutableStateFlow(LiveTrip())
    val live: StateFlow<LiveTrip> = _live

    private val points = mutableListOf<String>()
    private var last: Location? = null
    private var meters = 0.0

    /** Ignore GPS fixes that are less accurate than this (meters). */
    private const val MAX_ACCURACY = 50f

    /** Ignore movement smaller than this (meters). Cuts GPS jitter while parked. */
    private const val MIN_STEP = 10.0

    /** Drives shorter than this are thrown away (meters). */
    private const val MIN_TRIP = 50.0

    @Synchronized
    fun begin(now: Long) {
        points.clear()
        last = null
        meters = 0.0
        _live.value = LiveTrip(active = true, startTime = now)
    }

    @Synchronized
    fun onLocation(loc: Location) {
        if (!_live.value.active) return
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY) return
        val prev = last
        if (prev != null) {
            val step = prev.distanceTo(loc).toDouble()
            if (step < MIN_STEP) return
            meters += step
        }
        last = loc
        points.add(String.format(Locale.US, "%.5f,%.5f,%d", loc.latitude, loc.longitude, loc.time))
        _live.value = _live.value.copy(meters = meters, points = points.size)
    }

    /** Ends the drive. Returns null if it was too short to keep. */
    @Synchronized
    fun finish(now: Long): FinishedTrip? {
        val state = _live.value
        val result = if (state.active && meters >= MIN_TRIP) {
            FinishedTrip(state.startTime, now, meters, points.joinToString(";"))
        } else {
            null
        }
        points.clear()
        last = null
        meters = 0.0
        _live.value = LiveTrip()
        return result
    }
}

class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { TrackingState.onLocation(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAndSave()
            return START_NOT_STICKY
        }
        // Android requires the notification quickly after a foreground start.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        if (!TrackingState.live.value.active) {
            TrackingState.begin(System.currentTimeMillis())
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                .setMinUpdateDistanceMeters(10f)
                .build()
            try {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                TrackingState.finish(System.currentTimeMillis())
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopAndSave() {
        client.removeLocationUpdates(callback)
        val finished = TrackingState.finish(System.currentTimeMillis())
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (finished == null) {
            stopSelf()
            return
        }
        val appContext = applicationContext
        scope.launch {
            AppDatabase.get(appContext).tripDao().insert(
                Trip(
                    startTime = finished.startTime,
                    endTime = finished.endTime,
                    distanceMeters = finished.meters,
                    route = finished.route
                )
            )
            stopSelf()
        }
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Drive recording", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Recording your drive")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "drive_recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "org.openmiles.app.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
