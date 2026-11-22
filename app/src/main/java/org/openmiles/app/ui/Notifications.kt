package org.openmiles.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.openmiles.app.domain.ReminderTime
import org.openmiles.app.tracking.Channels
import org.openmiles.app.tracking.DriveEndNotice
import org.openmiles.app.tracking.NotifySettings
import org.openmiles.app.tracking.VoiceSettings
import java.time.DayOfWeek

// ---------------------------------------------------------------------------
// Notifications: one place for every kind, like MileIQ's settings
// ---------------------------------------------------------------------------

@Composable
fun NotificationsSection() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var ask by remember { mutableStateOf(VoiceSettings.askOn(context)) }
    var weekly by remember { mutableStateOf(NotifySettings.weeklyOn(context)) }
    var day by remember { mutableStateOf(NotifySettings.weeklyDay(context)) }
    var hour by remember { mutableIntStateOf(NotifySettings.weeklyHour(context)) }
    var monthly by remember { mutableStateOf(NotifySettings.monthlyOn(context)) }
    var auto by remember { mutableStateOf(NotifySettings.autoClassifiedOn(context)) }
    var pickingTime by remember { mutableStateOf(false) }

    // Coming back from the phone's settings: the person may have allowed notifications there.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
                ask = VoiceSettings.askOn(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted || NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!allowed) {
            Toast.makeText(context, "Notifications are off for OpenMiles. You can allow them in app settings.", Toast.LENGTH_LONG).show()
        }
    }
    /** Turning any switch on asks for notification permission first, if Android needs it. */
    fun needPermission() {
        if (!allowed && Build.VERSION.SDK_INT >= 33) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Text("Notifications", style = MaterialTheme.typography.titleLarge)
    Text(
        "Made on your phone. Nothing is sent anywhere, and there is no email.",
        style = MaterialTheme.typography.bodySmall
    )
    if (!allowed) {
        Text(
            "Notifications are off for OpenMiles, so none of these can show.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (Build.VERSION.SDK_INT >= 33) {
                OutlinedButton(onClick = { needPermission() }) { Text("Allow notifications") }
            }
            TextButton(onClick = { openNotificationSettings(context) }) { Text("Open app settings") }
        }
    }

    Spacer(Modifier.height(8.dp))
    SwitchRow(
        title = "Drive ended: business or personal?",
        detail = "Pops up at the top of the screen when a drive is saved without a category, with " +
            "Business and Personal buttons. Tap one when you're parked. Never use your phone while driving.",
        checked = ask
    ) {
        VoiceSettings.setAsk(context, it)
        ask = it
        if (it) needPermission()
    }
    if (ask) {
        TextButton(onClick = {
            if (!DriveEndNotice.showTest(context)) {
                Toast.makeText(context, "Notifications are off for OpenMiles, so the test can't show.", Toast.LENGTH_LONG).show()
            }
        }) { Text("Show a test pop-up") }
    }

    SwitchRow(
        title = "Weekly summary",
        detail = "Miles, business miles and value for the past 7 days, plus drives still waiting for a category. " +
            "Skipped when there is nothing to say.",
        checked = weekly
    ) {
        NotifySettings.setWeekly(context, it)
        weekly = it
        if (it) needPermission()
    }
    if (weekly) {
        OutlinedButton(onClick = { pickingTime = true }) {
            Text("${ReminderTime.dayLabel(day)}s at ${ReminderTime.hourLabel(hour)}")
        }
    }

    SwitchRow(
        title = "Monthly summary",
        detail = "On the 1st at ${ReminderTime.hourLabel(ReminderTime.MONTH_HOUR)}, last month's miles and value.",
        checked = monthly
    ) {
        NotifySettings.setMonthly(context, it)
        monthly = it
        if (it) needPermission()
    }

    SwitchRow(
        title = "Drive classified by a rule",
        detail = "A quiet note, with no sound or pop-up, when work hours or a place picked the category. " +
            "Tap it to check or change the drive.",
        checked = auto
    ) {
        NotifySettings.setAutoClassified(context, it)
        auto = it
        if (it) needPermission()
    }

    Text(
        "To change the sound or pop-up for one kind, use your phone's notification settings. " +
            "Each kind above has its own entry there.",
        style = MaterialTheme.typography.bodySmall
    )
    TextButton(onClick = { openNotificationSettings(context) }) { Text("Phone notification settings") }

    if (pickingTime) {
        WeeklyTimeDialog(
            day = day,
            hour = hour,
            onDismiss = { pickingTime = false },
            onPick = { d, h ->
                NotifySettings.setWeeklyTime(context, d, h)
                day = d
                hour = h
                pickingTime = false
            }
        )
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun WeeklyTimeDialog(
    day: DayOfWeek,
    hour: Int,
    onDismiss: () -> Unit,
    onPick: (DayOfWeek, Int) -> Unit
) {
    var chosenDay by remember { mutableStateOf(day) }
    var chosenHour by remember { mutableIntStateOf(hour) }
    // The week as people in the US read it, Sunday first.
    val days = listOf(DayOfWeek.SUNDAY) + DayOfWeek.values().filter { it != DayOfWeek.SUNDAY }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("When should the weekly summary show?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Day", style = MaterialTheme.typography.titleSmall)
                days.forEach { d ->
                    TextButton(onClick = { chosenDay = d }) {
                        Text((if (d == chosenDay) "✓ " else "") + ReminderTime.dayLabel(d))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Time", style = MaterialTheme.typography.titleSmall)
                ReminderTime.HOURS.forEach { h ->
                    TextButton(onClick = { chosenHour = h }) {
                        Text((if (h == chosenHour) "✓ " else "") + ReminderTime.hourLabel(h))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(chosenDay, chosenHour) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Opens OpenMiles' page in the phone's notification settings, or the app page as a backup. */
private fun openNotificationSettings(context: Context) {
    Channels.ensure(context)
    try {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    } catch (e: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            )
        } catch (e2: Exception) {
            Toast.makeText(context, "Could not open settings.", Toast.LENGTH_LONG).show()
        }
    }
}
