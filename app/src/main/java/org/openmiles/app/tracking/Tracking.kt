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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openmiles.app.MainActivity
import org.openmiles.app.R
import org.openmiles.app.data.AppDatabase
import org.openmiles.app.data.Rules
import org.openmiles.app.data.Trip
import org.openmiles.app.domain.AutoTripMonitor
import org.openmiles.app.domain.CarLink
import org.openmiles.app.domain.Draft
import org.openmiles.app.domain.Geo
import org.openmiles.app.domain.Speech
import org.openmiles.app.domain.TripRecorder
import java.util.Locale

/** What the screen shows while a drive is being recorded. */
data class LiveTrip(
    val active: Boolean = false,
    val startTime: Long = 0L,
    val meters: Double = 0.0,
    val points: Int = 0,
    /** True when the drive was started by automatic detection. */
    val auto: Boolean = false,
    /** True when no usable GPS fix has arrived for a while (tunnel, garage, phone in a bag). */
    val gpsWeak: Boolean = false
)

/** A finished drive, ready to save, and where the car ended up. */
data class FinishedTrip(
    val startTime: Long,
    val endTime: Long,
    val meters: Double,
    val route: String,
    val parkedAt: TripRecorder.Fix?
)

/** Shared between the recording service and the screen. All the math lives in domain classes. */
object TrackingState {
    private val _live = MutableStateFlow(LiveTrip())
    val live: StateFlow<LiveTrip> = _live

    private val recorder = TripRecorder()
    private var monitor: AutoTripMonitor? = null
    private var seed: TripRecorder.Fix? = null
    private var lastGoodFixMs: Long = 0L
    /** When the chosen car's Bluetooth disconnected during this drive, or null. */
    private var carOffAtMs: Long? = null
    /** True once "Drive started" has been said (or did not need saying) for this drive. */
    private var startAnnounced = false

    /** Farthest the parking spot may be from the first GPS fix and still count as the start. */
    private const val SEED_MAX_M = 2_000.0
    private const val GPS_WEAK_MS = 45_000L

    @Synchronized
    fun begin(now: Long, auto: Boolean, parkedAt: TripRecorder.Fix?) {
        recorder.reset()
        monitor = if (auto) AutoTripMonitor(now) else null
        seed = if (auto) parkedAt else null
        lastGoodFixMs = now
        carOffAtMs = null
        startAnnounced = false
        _live.value = LiveTrip(active = true, startTime = now, auto = auto)
    }

    /** The chosen car's Bluetooth disconnected. Only automatic drives end because of it. */
    @Synchronized
    fun onCarDisconnected(now: Long) {
        if (isAutoActive() && carOffAtMs == null) carOffAtMs = now
    }

    /** The chosen car's Bluetooth is back, so a short drop does not end the drive. */
    @Synchronized
    fun onCarConnected() {
        carOffAtMs = null
    }

    /**
     * True once per drive, when it is time to say "Drive started". A drive you start yourself is
     * announced at once. An automatic one waits until it has gone faster than walking speed, so a
     * walk past the parking circle is never announced as a drive.
     */
    @Synchronized
    fun takeStartAnnouncement(): Boolean {
        val state = _live.value
        if (!state.active || startAnnounced) return false
        val confirmed = !state.auto || (monitor?.maxSpeedMps ?: 0.0) >= AutoTripMonitor.DRIVE_SPEED_MPS
        if (confirmed) startAnnounced = true
        return confirmed
    }

    @Synchronized
    fun isAutoActive(): Boolean = _live.value.active && _live.value.auto

    @Synchronized
    fun onLocation(loc: Location) {
        if (!_live.value.active) return
        val time = if (loc.time > 0L) loc.time else System.currentTimeMillis()
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else null
        val fix = TripRecorder.Fix(loc.latitude, loc.longitude, time, accuracy)
        val goodEnough = accuracy == null || accuracy <= TripRecorder.MAX_ACCURACY_M
        if (!goodEnough) return
        lastGoodFixMs = maxOf(lastGoodFixMs, time)
        if (_live.value.gpsWeak) _live.value = _live.value.copy(gpsWeak = false)

        monitor?.onFix(fix.lat, fix.lng, time, if (loc.hasSpeed()) loc.speed else null)

        // Detection notices a drive a little late. If the car was parked close by, count the
        // distance from the parking spot so the start of the drive is not lost.
        val s = seed
        if (s != null) {
            seed = null
            if (s.timeMs < time && Geo.distanceMeters(s.lat, s.lng, fix.lat, fix.lng) <= SEED_MAX_M) {
                recorder.add(s.copy(accuracyM = null))
            }
        }

        if (recorder.add(fix)) {
            _live.value = _live.value.copy(meters = recorder.meters, points = recorder.pointCount)
        }
    }

    /** Shows a "no GPS signal" note on screen when fixes stop arriving. Returns true if it changed. */
    @Synchronized
    fun updateGpsStatus(now: Long): Boolean {
        if (!_live.value.active) return false
        val weak = now - lastGoodFixMs >= GPS_WEAK_MS
        if (weak == _live.value.gpsWeak) return false
        _live.value = _live.value.copy(gpsWeak = weak)
        return true
    }

    /** For automatic drives only: should the drive end now? */
    @Synchronized
    fun checkAuto(now: Long): AutoTripMonitor.Decision? {
        if (!_live.value.active) return null
        val off = carOffAtMs
        if (monitor != null && CarLink.shouldEnd(off, now) && off != null) {
            return AutoTripMonitor.Decision.CarOff(off)
        }
        return monitor?.check(now)
    }

    /** The drive so far, to save in case Android closes the app. */
    @Synchronized
    fun snapshot(now: Long): Draft? {
        val state = _live.value
        if (!state.active) return null
        return Draft(
            startMs = state.startTime,
            endMs = maxOf(state.startTime, recorder.lastFix?.timeMs ?: now),
            meters = recorder.meters,
            route = recorder.routeString(),
            auto = state.auto,
            maxSpeedMps = monitor?.maxSpeedMps ?: 0.0
        )
    }

    /**
     * Ends the drive. Returns null if it should not be kept (too short, or an automatic drive
     * that turned out to be a walk). A drive the person stopped themselves uses the manual rule.
     */
    @Synchronized
    fun finish(
        now: Long,
        endMs: Long? = null,
        userStopped: Boolean = false,
        knowWhereParked: Boolean = true
    ): FinishedTrip? {
        val state = _live.value
        val draft = if (state.active) {
            Draft(
                startMs = state.startTime,
                endMs = maxOf(state.startTime, endMs ?: now),
                meters = recorder.meters,
                route = recorder.routeString(),
                auto = state.auto && !userStopped,
                maxSpeedMps = monitor?.maxSpeedMps ?: 0.0
            )
        } else {
            null
        }
        // After GPS was lost, the last fix is not where the car is, so don't claim it is.
        val parkedAt = if (knowWhereParked) recorder.lastFix else null
        recorder.reset()
        monitor = null
        seed = null
        carOffAtMs = null
        _live.value = LiveTrip()
        return if (draft != null && draft.worthKeeping()) {
            FinishedTrip(draft.startMs, draft.endMs, draft.meters, draft.route, parkedAt)
        } else {
            null
        }
    }
}

class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var ticker: Job? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { TrackingState.onLocation(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAndSave(endMs = null, userStopped = true, reason = "you tapped Stop")
                return START_NOT_STICKY
            }
            ACTION_AUTO_STOP -> {
                if (TrackingState.isAutoActive()) {
                    stopAndSave(endMs = null, userStopped = false, reason = "you left the vehicle")
                } else if (!TrackingState.live.value.active) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        val auto = intent?.action == ACTION_AUTO_START
        // Android requires the notification quickly after a foreground start.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(auto),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Exception) {
            // Android refused (for example, a permission was taken away). Record nothing.
            DiagLog.add(this, "Android refused to start recording: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!TrackingState.live.value.active) {
            if (auto && !AutoDetect.isEnabled(this)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            // Save any drive that was cut off last time before starting a new one.
            val leftover = DraftStore.take(this)
            if (leftover != null) {
                val appContext = applicationContext
                scope.launch { DraftStore.saveRecovered(appContext, leftover) }
            }

            val now = System.currentTimeMillis()
            TrackingState.begin(now, auto, if (auto) AutoDetect.parkedAt(this) else null)
            DiagLog.add(this, if (auto) "Recording started automatically" else "Recording started (you tapped Start)")
            announceStartIfReady()
            // No distance filter: fixes keep coming while parked, which is how a real stop is
            // told apart from a lost signal. Tiny moves are still ignored by TripRecorder.
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).build()
            try {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                // Permission was taken away. Nothing was recorded, so just stop.
                DiagLog.add(this, "GPS blocked: location permission missing")
                TrackingState.finish(System.currentTimeMillis())
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            startTicker()
        }
        return START_NOT_STICKY
    }

    /** Every 30 seconds: save the drive so far, and end automatic drives when it's time. */
    private fun startTicker() {
        ticker?.cancel()
        val appContext = applicationContext
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                val draft = TrackingState.snapshot(now) ?: break
                DraftStore.write(appContext, draft)
                announceStartIfReady()
                if (TrackingState.updateGpsStatus(now)) {
                    DiagLog.add(
                        appContext,
                        if (TrackingState.live.value.gpsWeak) "GPS signal lost" else "GPS signal back"
                    )
                }
                when (val decision = TrackingState.checkAuto(now)) {
                    is AutoTripMonitor.Decision.Parked -> {
                        withContext(Dispatchers.Main) {
                            stopAndSave(decision.endMs, userStopped = false, reason = "parked for 5 minutes")
                        }
                        break
                    }
                    is AutoTripMonitor.Decision.GpsLost -> {
                        withContext(Dispatchers.Main) {
                            stopAndSave(
                                decision.endMs, userStopped = false, gpsLost = true,
                                reason = "no GPS for 30 minutes"
                            )
                        }
                        break
                    }
                    is AutoTripMonitor.Decision.CarOff -> {
                        withContext(Dispatchers.Main) {
                            stopAndSave(decision.endMs, userStopped = false, reason = "car Bluetooth disconnected")
                        }
                        break
                    }
                    AutoTripMonitor.Decision.NotADrive -> {
                        withContext(Dispatchers.Main) {
                            stopAndSave(endMs = null, userStopped = false, reason = "never faster than 15 mph")
                        }
                        break
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Says "Drive started" once, if spoken updates are on. */
    private fun announceStartIfReady() {
        if (!VoiceSettings.speakOn(this)) return
        if (!TrackingState.takeStartAnnouncement()) return
        val appContext = applicationContext
        scope.launch { Speaker.say(appContext, Speech.started()) }
    }

    private fun stopAndSave(
        endMs: Long?,
        userStopped: Boolean,
        reason: String,
        gpsLost: Boolean = false
    ) {
        ticker?.cancel()
        ticker = null
        client.removeLocationUpdates(callback)
        val before = TrackingState.snapshot(System.currentTimeMillis())
        val finished = TrackingState.finish(
            System.currentTimeMillis(), endMs, userStopped, knowWhereParked = !gpsLost
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (before != null) {
            val miles = before.meters / 1609.344
            val result = when {
                finished != null -> String.format(Locale.US, "saved, %.1f mi", finished.meters / 1609.344)
                before.auto && !userStopped && before.meters < AutoTripMonitor.MIN_AUTO_TRIP_M ->
                    String.format(Locale.US, "not kept, only %.2f mi", miles)
                before.auto && !userStopped ->
                    String.format(Locale.US, "not kept, top speed %.0f mph", before.maxSpeedMps * 2.23694)
                else -> String.format(Locale.US, "not kept, only %.2f mi", miles)
            }
            DiagLog.add(this, "Drive ended ($reason): $result")
        }
        val appContext = applicationContext
        val wasAuto = before?.auto == true && !userStopped
        scope.launch {
            var savedId: Long? = null
            var savedCategory: Long? = null
            try {
                if (finished != null) {
                    val db = AppDatabase.get(appContext)
                    val rules = Rules.load(appContext, db)
                    if (wasAuto && rules.ignoresRoute(finished.route)) {
                        // Both ends are inside a place the person asked to ignore.
                        DiagLog.add(appContext, "Drive not kept: it started and ended in an ignored place")
                    } else {
                        val categoryId = rules.categoryForRoute(finished.route, finished.startTime)
                        savedCategory = categoryId
                        savedId = db.tripDao().insert(
                            Trip(
                                startTime = finished.startTime,
                                endTime = finished.endTime,
                                distanceMeters = finished.meters,
                                route = finished.route,
                                categoryId = categoryId
                            )
                        )
                        if (categoryId != null) DiagLog.add(appContext, "Drive classified by a rule")
                    }
                    // Remember where the car is, so the next drive can be noticed quickly.
                    AutoDetect.onParked(appContext, finished.parkedAt)
                }
                // If GPS was lost, the old parking spot is no longer trustworthy.
                if (gpsLost) AutoDetect.forgetParking(appContext)
                DraftStore.clear(appContext)
                val id = savedId
                if (id != null && finished != null) tellAboutSavedDrive(appContext, id, finished.meters, savedCategory)
            } finally {
                // Always release the service, even if saving failed. Saying "Drive saved" can take
                // a few seconds, and a new drive may have started meanwhile (the car reconnected).
                // Then the service is busy again and must keep running.
                if (!TrackingState.live.value.active) stopSelf()
            }
        }
    }

    /** The optional "Business or personal?" notification and the spoken "Drive saved". */
    private suspend fun tellAboutSavedDrive(appContext: Context, tripId: Long, meters: Double, categoryId: Long?) {
        try {
            val ask = categoryId == null && VoiceSettings.askOn(appContext)
            if (ask) DriveEndNotice.show(appContext, tripId, meters)
            if (VoiceSettings.speakOn(appContext)) {
                val name = categoryId?.let { id ->
                    AppDatabase.get(appContext).categoryDao().all().firstOrNull { it.id == id }?.name
                }
                Speaker.say(appContext, Speech.saved(meters, name, ask))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Talking about a drive must never stop it from being saved. It is already saved.
        }
    }

    override fun onDestroy() {
        ticker?.cancel()
        client.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun buildNotification(auto: Boolean): Notification {
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
            .setContentText(if (auto) "Drive detected. Recording." else "Recording your drive")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "drive_recording"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 30_000L
        private const val ACTION_STOP = "org.openmiles.app.STOP"
        private const val ACTION_AUTO_START = "org.openmiles.app.AUTO_START"
        private const val ACTION_AUTO_STOP = "org.openmiles.app.AUTO_STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            )
        }

        /** Called when a drive is detected. Android may refuse; then nothing happens. */
        fun autoStart(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TrackingService::class.java).setAction(ACTION_AUTO_START)
                )
            } catch (e: Exception) {
                // Not allowed right now (for example, a battery saver). The next signal tries again.
                DiagLog.add(context, "Android blocked the automatic start: ${e.javaClass.simpleName}")
            }
        }

        /** Called when Android says the drive ended. Only ends drives that started on their own. */
        fun autoStop(context: Context) {
            try {
                context.startService(
                    Intent(context, TrackingService::class.java).setAction(ACTION_AUTO_STOP)
                )
            } catch (e: Exception) {
                // The service is not running, so there is nothing to stop.
            }
        }
    }
}
