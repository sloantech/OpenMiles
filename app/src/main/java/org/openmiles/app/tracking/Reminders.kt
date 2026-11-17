package org.openmiles.app.tracking

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.openmiles.app.MainActivity
import org.openmiles.app.R
import org.openmiles.app.data.AppDatabase
import org.openmiles.app.domain.ReminderTime

/** A gentle weekly note when drives are waiting to be classified. Nothing is sent anywhere. */
object ClassifyReminder {
    private const val PREFS = "reminders"
    private const val KEY_ON = "classify_on"
    private const val CHANNEL = "reminders"
    private const val NOTIFICATION_ID = 2
    const val ACTION = "org.openmiles.app.CLASSIFY_REMINDER"

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        if (on) schedule(context) else cancel(context)
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 20,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Sets the weekly alarm. Safe to call often: it replaces the previous one. */
    fun schedule(context: Context) {
        if (!isOn(context)) return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.setInexactRepeating(
            AlarmManager.RTC,
            ReminderTime.next(System.currentTimeMillis()),
            AlarmManager.INTERVAL_DAY * 7,
            pendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    fun notifyIfNeeded(context: Context, unclassified: Int) {
        val text = ReminderTime.text(unclassified) ?: return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 21, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications are not allowed. The app shows the count on the Drives tab anyway.
        }
    }
}

/** Fires once a week. Counts unclassified drives on a background thread and shows the note. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClassifyReminder.ACTION || !ClassifyReminder.isOn(context)) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ClassifyReminder.notifyIfNeeded(app, AppDatabase.get(app).tripDao().countUnclassified())
            } catch (e: Exception) {
                // A reminder must never crash anything.
            } finally {
                pending.finish()
            }
        }
    }
}
