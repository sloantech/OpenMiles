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
import org.openmiles.app.domain.Calc
import org.openmiles.app.domain.ReminderTime
import org.openmiles.app.domain.Summaries
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Every OpenMiles notification type, each on its own Android channel, so people can change
 * sound and pop-ups for one type in the phone's settings without touching the others.
 * Nothing is ever sent anywhere: these are all made on the phone.
 */
object Channels {
    /** Pops up at the top of the screen. Created new, because Android never raises an old channel. */
    const val DRIVE_ENDED = "drive_ended_popup"
    const val SUMMARIES = "reminders"
    const val AUTO_CLASSIFIED = "auto_classified"
    private const val OLD_DRIVE_ENDED = "drive_ended"

    /** Safe to call often. Makes sure every type shows up in the phone's notification settings. */
    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.deleteNotificationChannel(OLD_DRIVE_ENDED)
            manager.createNotificationChannels(
                listOf(
                    NotificationChannel(DRIVE_ENDED, "Drive ended", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Business or personal? Pops up when a drive is saved without a category."
                    },
                    NotificationChannel(SUMMARIES, "Weekly and monthly summaries", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Miles, value and drives still waiting for a category."
                    },
                    NotificationChannel(AUTO_CLASSIFIED, "Drive classified by a rule", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "A quiet note when work hours or a place picked the category."
                        setShowBadge(false)
                    }
                )
            )
        } catch (e: Exception) {
            // Notifications must never crash anything.
        }
    }
}

/** The on and off switches for the notifications. Stored only on this phone. */
object NotifySettings {
    private const val PREFS = "reminders"
    private const val KEY_WEEKLY = "classify_on"
    private const val KEY_DAY = "weekly_day"
    private const val KEY_HOUR = "weekly_hour"
    private const val KEY_MONTHLY = "monthly_on"
    private const val KEY_AUTO = "auto_classified_on"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun weeklyOn(context: Context): Boolean = prefs(context).getBoolean(KEY_WEEKLY, true)
    fun setWeekly(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEEKLY, on).apply()
        Summary.schedule(context)
    }

    fun weeklyDay(context: Context): DayOfWeek =
        DayOfWeek.of(prefs(context).getInt(KEY_DAY, ReminderTime.DAY.value).coerceIn(1, 7))

    fun weeklyHour(context: Context): Int =
        prefs(context).getInt(KEY_HOUR, ReminderTime.HOUR).coerceIn(0, 23)

    fun setWeeklyTime(context: Context, day: DayOfWeek, hour: Int) {
        prefs(context).edit().putInt(KEY_DAY, day.value).putInt(KEY_HOUR, hour.coerceIn(0, 23)).apply()
        Summary.schedule(context)
    }

    fun monthlyOn(context: Context): Boolean = prefs(context).getBoolean(KEY_MONTHLY, false)
    fun setMonthly(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONTHLY, on).apply()
        Summary.schedule(context)
    }

    fun autoClassifiedOn(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, false)
    fun setAutoClassified(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
}

/** The weekly and monthly summaries. Each alarm fires once, then sets the next one. */
object Summary {
    private const val WEEKLY_ID = 2
    private const val MONTHLY_ID = 3
    const val ACTION_WEEKLY = "org.openmiles.app.CLASSIFY_REMINDER"
    const val ACTION_MONTHLY = "org.openmiles.app.MONTHLY_SUMMARY"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private fun pendingIntent(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        context, code,
        Intent(context, ReminderReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Sets both alarms to their next time, or cancels the ones that are off. Safe to call often:
     * each call replaces the previous alarm. One-time alarms (set again after each one fires)
     * keep the hour right across daylight saving time changes.
     */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val weekly = pendingIntent(context, ACTION_WEEKLY, 20)
        val monthly = pendingIntent(context, ACTION_MONTHLY, 23)
        try {
            if (NotifySettings.weeklyOn(context)) {
                val at = ReminderTime.next(
                    now, ZoneId.systemDefault(), NotifySettings.weeklyDay(context), NotifySettings.weeklyHour(context)
                )
                alarms.set(AlarmManager.RTC, at, weekly)
            } else {
                alarms.cancel(weekly)
            }
            if (NotifySettings.monthlyOn(context)) {
                alarms.set(AlarmManager.RTC, ReminderTime.nextMonthStart(now), monthly)
            } else {
                alarms.cancel(monthly)
            }
        } catch (e: Exception) {
            // An alarm that can't be set must never crash anything.
        }
    }

    suspend fun showWeekly(context: Context) {
        val db = AppDatabase.get(context)
        val now = System.currentTimeMillis()
        val waiting = db.tripDao().countUnclassified()
        val s = build(context, now - 7 * DAY_MS, now)
        val text = Summaries.weeklyText(s, waiting) ?: return
        post(context, WEEKLY_ID, "Your week in miles", text, waiting)
    }

    suspend fun showMonthly(context: Context) {
        val db = AppDatabase.get(context)
        val now = System.currentTimeMillis()
        val month = ReminderTime.previousMonth(now)
        val (from, to) = ReminderTime.bounds(month)
        val waiting = db.tripDao().countUnclassified()
        val name = month.from.month.getDisplayName(TextStyle.FULL, Locale.US)
        val text = Summaries.monthlyText(name, build(context, from, to), waiting) ?: return
        post(context, MONTHLY_ID, "Your $name in miles", text, waiting)
    }

    private suspend fun build(context: Context, fromMs: Long, toMs: Long) = run {
        val db = AppDatabase.get(context)
        val kinds = db.categoryDao().all().associate { it.id to it.kind }
        val rates = db.rateDao().all()
        Summaries.build(
            db.tripDao().summariesBetween(fromMs, toMs).map {
                Triple(it.categoryId, it.distanceMeters, Calc.toDate(it.startTime))
            },
            kindOf = { id -> id?.let { kinds[it] } },
            rateOf = { kind, date -> Calc.rateFor(kind, date, rates) }
        )
    }

    private fun post(context: Context, id: Int, title: String, text: String, waiting: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        Channels.ensure(context)
        val open = PendingIntent.getActivity(
            context, 21, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, Channels.SUMMARIES)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            // The number some phones show on the app icon: drives still waiting for a category.
            .setNumber(waiting.coerceAtLeast(0))
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Notifications are not allowed. The app shows the count on the Drives tab anyway.
        }
    }
}

/** The quiet "Marked Business by a rule" note. Off unless the person turns it on. */
object AutoClassifiedNotice {
    private fun notificationId(tripId: Long): Int = 200_000 + (tripId % 100_000L).toInt()

    fun show(context: Context, tripId: Long, meters: Double, categoryName: String) {
        if (!NotifySettings.autoClassifiedOn(context)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        Channels.ensure(context)
        val open = PendingIntent.getActivity(
            context, 24, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, Channels.AUTO_CLASSIFIED)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(Summaries.autoClassifiedText(meters, categoryName))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(notificationId(tripId), notification)
        } catch (e: SecurityException) {
            // Notifications are not allowed. Nothing else to do.
        }
    }

    fun cancel(context: Context, tripId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(tripId))
    }
}

/** Fires for the weekly and monthly summaries. Works on a background thread, then sets the next alarm. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Summary.ACTION_WEEKLY && action != Summary.ACTION_MONTHLY) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (action == Summary.ACTION_WEEKLY && NotifySettings.weeklyOn(app)) Summary.showWeekly(app)
                if (action == Summary.ACTION_MONTHLY && NotifySettings.monthlyOn(app)) Summary.showMonthly(app)
            } catch (e: Exception) {
                // A summary must never crash anything.
            } finally {
                Summary.schedule(app)
                pending.finish()
            }
        }
    }
}
