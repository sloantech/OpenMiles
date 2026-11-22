package org.openmiles.app.tracking

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openmiles.app.MainActivity
import org.openmiles.app.R
import org.openmiles.app.data.AppDatabase
import org.openmiles.app.data.Kind
import org.openmiles.app.domain.Speech
import kotlin.coroutines.resume

/** The two optional "talk to me" settings. Both are off until the person turns them on. */
object VoiceSettings {
    private const val PREFS = "voice"
    private const val KEY_SPEAK = "speak"
    private const val KEY_ASK = "ask"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Say "Drive started" and "Drive saved, 12.4 miles" out loud. */
    fun speakOn(context: Context): Boolean = prefs(context).getBoolean(KEY_SPEAK, false)
    fun setSpeak(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SPEAK, on).apply()

    /**
     * Show Business and Personal buttons on a notification when an unclassified drive is saved.
     * Until the person picks on or off, it follows automatic detection: on once that is on,
     * like MileIQ, because that is when drives show up without the person noticing.
     */
    fun askOn(context: Context): Boolean {
        val p = prefs(context)
        return if (p.contains(KEY_ASK)) p.getBoolean(KEY_ASK, false) else AutoDetect.isEnabled(context)
    }
    fun setAsk(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ASK, on).apply()
}

/**
 * Speaks short updates with the phone's own text-to-speech voice. It only uses a voice that is
 * installed on the phone, never one that needs the internet. If there is no such voice, it stays
 * quiet and says why in the detection log.
 */
object Speaker {
    private const val TIMEOUT_MS = 15_000L

    /** Says [text] and waits until it is done (at most 15 seconds). Never throws. */
    suspend fun say(context: Context, text: String) {
        val app = context.applicationContext
        try {
            withTimeoutOrNull(TIMEOUT_MS) {
                withContext(Dispatchers.Main) { speakNow(app, text) }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DiagLog.add(app, "Could not speak: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun speakNow(app: Context, text: String) = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        val audio = app.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        // Music turns down while it speaks, then comes back.
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        var tts: TextToSpeech? = null

        fun finish() {
            if (!done.compareAndSet(false, true)) return
            try {
                audio?.abandonAudioFocusRequest(focus)
            } catch (e: Exception) {
                // Nothing to give back.
            }
            try {
                tts?.shutdown()
            } catch (e: Exception) {
                // Already closed.
            }
            if (cont.isActive) cont.resume(Unit)
        }
        cont.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { finish() } }

        tts = TextToSpeech(app) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                DiagLog.add(app, "Could not speak: the phone's text-to-speech is not available")
                finish()
                return@TextToSpeech
            }
            if (!useOfflineVoice(engine)) {
                DiagLog.add(app, "Could not speak: no voice is installed on the phone for offline use")
                finish()
                return@TextToSpeech
            }
            engine.setAudioAttributes(attributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finish()
                @Deprecated("Required by older Android versions")
                override fun onError(utteranceId: String?) = finish()
                override fun onError(utteranceId: String?, errorCode: Int) = finish()
            })
            try {
                audio?.requestAudioFocus(focus)
            } catch (e: Exception) {
                // Speak anyway; it will just be over the music.
            }
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "openmiles")
            if (result != TextToSpeech.SUCCESS) finish()
        }
    }

    /**
     * Picks a voice in the phone's language that works without the internet. Returns false if
     * there is none, so nothing is ever sent off the phone to be spoken.
     */
    private fun useOfflineVoice(engine: TextToSpeech): Boolean {
        val language = Locale.getDefault().language
        val voices = try {
            engine.voices.orEmpty()
        } catch (e: Exception) {
            emptySet()
        }
        val current = try {
            engine.voice
        } catch (e: Exception) {
            null
        }
        if (current != null && !current.isNetworkConnectionRequired && current.locale.language == language) return true
        val offline = voices
            .filter { !it.isNetworkConnectionRequired && it.locale.language == language }
            .filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .maxByOrNull { it.quality }
            ?: voices
                .filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
                .filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
                .maxByOrNull { it.quality }
            ?: return false
        return engine.setVoice(offline) == TextToSpeech.SUCCESS
    }
}

/**
 * The "Drive saved. Business or personal?" notification. Tapping a button classifies the drive
 * without opening the app. Nothing is sent anywhere.
 */
object DriveEndNotice {
    const val ACTION = "org.openmiles.app.CLASSIFY_FROM_NOTICE"
    const val EXTRA_TRIP = "trip"
    const val EXTRA_KIND = "kind"
    /** The test notification's buttons carry this, which matches no drive. */
    const val TEST_TRIP = -7L
    private const val TEST_ID = 999

    private fun notificationId(tripId: Long): Int = 1000 + (tripId % 100_000L).toInt()

    /** [waiting] is every drive still without a category, for the number on the app icon. */
    fun show(context: Context, tripId: Long, meters: Double, waiting: Int = 1) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        Channels.ensure(context)
        fun button(kind: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId(tripId) * 2 + code,
            Intent(context, ClassifyActionReceiver::class.java)
                .setAction(ACTION)
                .putExtra(EXTRA_TRIP, tripId)
                .putExtra(EXTRA_KIND, kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            context, 22, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, Channels.DRIVE_ENDED)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Drive saved, ${Speech.miles(meters)}. Business or personal?")
            // High priority makes it pop up at the top of the screen on Android 7 and older too.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setNumber(waiting.coerceAtLeast(1))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Business", button(Kind.BUSINESS, 0))
            .addAction(0, "Personal", button(Kind.PERSONAL, 1))
            .build()
        try {
            manager.notify(notificationId(tripId), notification)
        } catch (e: SecurityException) {
            // Notifications are not allowed. The drive is still waiting on the Drives tab.
        }
    }

    /**
     * A sample pop-up from Settings, so people can see what it looks like and check their phone
     * shows it. Its buttons point at no drive, so tapping them changes nothing.
     */
    fun showTest(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        Channels.ensure(context)
        fun button(kind: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            context, 30 + code,
            Intent(context, ClassifyActionReceiver::class.java)
                .setAction(ACTION).putExtra(EXTRA_TRIP, TEST_TRIP).putExtra(EXTRA_KIND, kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, Channels.DRIVE_ENDED)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Test: Drive saved, 12.4 miles. Business or personal?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(0, "Business", button(Kind.BUSINESS, 0))
            .addAction(0, "Personal", button(Kind.PERSONAL, 1))
            .build()
        return try {
            manager.notify(TEST_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun cancel(context: Context, tripId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(tripId))
    }

    fun cancelTest(context: Context) {
        NotificationManagerCompat.from(context).cancel(TEST_ID)
    }
}

/** Handles the Business and Personal buttons. Only our own notification can reach it. */
class ClassifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DriveEndNotice.ACTION) return
        val tripId = intent.getLongExtra(DriveEndNotice.EXTRA_TRIP, -1L)
        val kind = intent.getStringExtra(DriveEndNotice.EXTRA_KIND)
        if (tripId == DriveEndNotice.TEST_TRIP) {
            DriveEndNotice.cancelTest(context.applicationContext)
            return
        }
        if (tripId <= 0L || (kind != Kind.BUSINESS && kind != Kind.PERSONAL)) return
        val app = context.applicationContext
        DriveEndNotice.cancel(app, tripId)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.get(app)
                val category = db.categoryDao().builtIn(kind) ?: return@launch
                val trip = db.tripDao().get(tripId) ?: return@launch
                // Never overwrite a category picked in the meantime.
                if (trip.categoryId == null) {
                    db.tripDao().setCategory(tripId, category.id)
                    DiagLog.add(app, "Drive marked ${Kind.label(kind)} from the notification")
                }
            } catch (e: Exception) {
                // A button press must never crash anything. The drive stays unclassified.
            } finally {
                pending.finish()
            }
        }
    }
}
