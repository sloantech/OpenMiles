package org.openmiles.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.openmiles.app.domain.CarLink
import org.openmiles.app.tracking.CarBluetooth
import org.openmiles.app.tracking.Speaker
import org.openmiles.app.tracking.VoiceSettings

// ---------------------------------------------------------------------------
// Car Bluetooth: shown inside "Automatic drive detection" once it is on
// ---------------------------------------------------------------------------

@Composable
fun CarBluetoothSection() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(CarBluetooth.isOn(context)) }
    var cars by remember { mutableStateOf(CarBluetooth.cars(context)) }
    var picking by remember { mutableStateOf<List<CarLink.Car>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var batteryOk by remember { mutableStateOf(CarBluetooth.batteryUnrestricted(context)) }

    // The battery setting can change in the phone's Settings while the app is in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) batteryOk = CarBluetooth.batteryUnrestricted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openPicker(askIfNeeded: () -> Unit) {
        message = null
        when (val paired = CarBluetooth.paired(context)) {
            is CarBluetooth.Paired.Devices ->
                if (paired.cars.isEmpty()) {
                    message = "No paired devices yet. Pair your phone with your car in your phone's Bluetooth settings first."
                } else {
                    picking = paired.cars
                }
            CarBluetooth.Paired.NoBluetooth -> message = "This phone has no Bluetooth."
            CarBluetooth.Paired.BluetoothOff -> message = "Turn on Bluetooth, then try again."
            CarBluetooth.Paired.NoPermission -> askIfNeeded()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openPicker { message = "OpenMiles still can't see your paired devices." }
        } else {
            message = "OpenMiles needs \"Nearby devices\" to see your car's Bluetooth. " +
                "You can allow it in app settings, under Permissions."
        }
    }

    fun startPicking() = openPicker {
        if (Build.VERSION.SDK_INT >= 31) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    Spacer(Modifier.height(8.dp))
    Text("Car Bluetooth", style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Start when my car's Bluetooth connects",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(
            checked = on,
            onCheckedChange = { turnOn ->
                if (turnOn) {
                    startPicking()
                } else {
                    CarBluetooth.save(context, false, cars)
                    on = false
                    message = null
                }
            }
        )
    }
    if (on) {
        Text("Your car: ${cars.joinToString(", ") { it.name }}", fontWeight = FontWeight.SemiBold)
        Text(
            "A drive starts when your phone connects to this car, and ends a minute after it " +
                "disconnects. Motion detection still works as a backup, for example in a rental.",
            style = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = { startPicking() }) { Text("Change car") }
        if (!batteryOk) {
            Text(
                "Android only lets Bluetooth start a drive while the app is closed if OpenMiles " +
                    "battery use is Unrestricted. Open app settings, then Battery, and choose Unrestricted.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { openSettingsPage(context) }) { Text("Open app settings") }
        }
    } else {
        Text(
            "Optional. Uses the list of devices already paired with your phone. " +
                "Nothing is scanned, connected or sent anywhere.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    message?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (it.contains("app settings")) {
            TextButton(onClick = { openSettingsPage(context) }) { Text("Open app settings") }
        }
    }

    picking?.let { paired ->
        val chosen = remember(paired) {
            mutableStateOf(cars.map { it.address }.filter { a -> paired.any { it.address == a } }.toSet())
        }
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("Which is your car?") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Pick your car's Bluetooth. Don't pick headphones or a watch, or a drive " +
                            "would start whenever they connect.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    paired.forEach { car ->
                        val checked = car.address in chosen.value
                        val canAdd = checked || chosen.value.size < CarLink.MAX_CARS
                        fun toggle() {
                            if (!canAdd) return
                            chosen.value = if (checked) chosen.value - car.address else chosen.value + car.address
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = canAdd) { toggle() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, enabled = canAdd, onCheckedChange = { toggle() })
                            Text(car.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = chosen.value.isNotEmpty(),
                    onClick = {
                        val picked = paired.filter { it.address in chosen.value }
                        CarBluetooth.save(context, true, picked)
                        cars = picked
                        on = true
                        batteryOk = CarBluetooth.batteryUnrestricted(context)
                        picking = null
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------------------
// Spoken updates
// ---------------------------------------------------------------------------

@Composable
fun VoiceSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var speak by remember { mutableStateOf(VoiceSettings.speakOn(context)) }
    var testing by remember { mutableStateOf(false) }

    Text("Spoken updates", style = MaterialTheme.typography.titleLarge)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Say when a drive starts and ends",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(checked = speak, onCheckedChange = {
            VoiceSettings.setSpeak(context, it)
            speak = it
        })
    }
    Text(
        if (speak) {
            "On. You'll hear \"Drive started\" and \"Drive saved, 12.4 miles\". Music turns down while it talks. " +
                "It uses your phone's own voice and only one that works offline."
        } else {
            "Off. Optional. Uses your phone's own voice, only one that works offline."
        },
        style = MaterialTheme.typography.bodySmall
    )
    if (speak) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !testing,
                onClick = {
                    testing = true
                    scope.launch {
                        Speaker.say(context, "Drive saved. 12.4 miles.")
                        testing = false
                    }
                }
            ) { Text(if (testing) "Speaking..." else "Test the voice") }
            TextButton(onClick = { openVoiceSettings(context) }) { Text("Voice settings") }
        }
        Text(
            "Hear nothing? In Voice settings, download a voice for your language so it works offline. " +
                "The detection log below also says why it stayed quiet.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun openSettingsPage(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open settings.", Toast.LENGTH_LONG).show()
    }
}

private fun openVoiceSettings(context: Context) {
    try {
        context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open voice settings. Look for Text-to-speech in your phone's settings.", Toast.LENGTH_LONG).show()
    }
}
