package org.openmiles.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import org.openmiles.app.domain.CarLink

/**
 * Optional: start a drive the moment the phone connects to the car's Bluetooth, and end it when
 * the car disconnects and stays off for a minute. It works on top of automatic detection, which
 * still catches drives in other cars or when Bluetooth doesn't connect.
 *
 * Only the cars the person picks count. Their names and addresses stay in the app's private
 * storage. The app never scans for devices and never connects to anything.
 */
object CarBluetooth {
    private const val PREFS = "car_bluetooth"
    private const val KEY_ON = "on"
    private const val KEY_CARS = "cars"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun cars(context: Context): List<CarLink.Car> = CarLink.decode(prefs(context).getString(KEY_CARS, null))

    fun save(context: Context, on: Boolean, cars: List<CarLink.Car>) {
        prefs(context).edit().putBoolean(KEY_ON, on && cars.isNotEmpty()).putString(KEY_CARS, CarLink.encode(cars)).apply()
        DiagLog.add(
            context,
            if (on && cars.isNotEmpty()) "Car Bluetooth start turned on (${cars.size} car${if (cars.size == 1) "" else "s"})"
            else "Car Bluetooth start turned off"
        )
    }

    /** Android 12 and newer ask for "Nearby devices" before an app can see paired devices. */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Android only lets a Bluetooth signal start recording when battery use is Unrestricted. */
    fun batteryUnrestricted(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (e: Exception) {
        false
    }

    sealed class Paired {
        data class Devices(val cars: List<CarLink.Car>) : Paired()
        object NoBluetooth : Paired()
        object BluetoothOff : Paired()
        object NoPermission : Paired()
    }

    /** The devices already paired with this phone. Nothing is scanned or connected. */
    @SuppressLint("MissingPermission") // checked by hasPermission() first
    fun paired(context: Context): Paired {
        if (!hasPermission(context)) return Paired.NoPermission
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return Paired.NoBluetooth
        return try {
            if (!adapter.isEnabled) return Paired.BluetoothOff
            val list = adapter.bondedDevices.orEmpty().mapNotNull { d ->
                val address = d.address?.uppercase() ?: return@mapNotNull null
                if (!CarLink.isAddress(address)) return@mapNotNull null
                CarLink.Car(address, CarLink.clean(d.name ?: "").ifBlank { address })
            }.sortedBy { it.name.lowercase() }
            Paired.Devices(list)
        } catch (e: SecurityException) {
            Paired.NoPermission
        }
    }

    /** Called by the receivers below for a connect or disconnect. */
    fun onSignal(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        if (!isOn(context) || !AutoDetect.isEnabled(context)) return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = try {
            device?.address?.uppercase()
        } catch (e: SecurityException) {
            null
        } ?: return
        val car = cars(context).firstOrNull { it.address == address } ?: return // not one of the chosen cars

        if (connected) {
            TrackingState.onCarConnected()
            if (AutoDetect.isPaused(context)) {
                DiagLog.add(context, "Car Bluetooth connected (${car.name}), but auto-detect is paused. Ignored.")
                return
            }
            DiagLog.add(context, "Car Bluetooth connected (${car.name})")
            AutoDetect.startIfIdle(context)
        } else {
            if (TrackingState.isAutoActive()) {
                DiagLog.add(context, "Car Bluetooth disconnected (${car.name}). Ending the drive if it stays off for a minute.")
            } else {
                DiagLog.add(context, "Car Bluetooth disconnected (${car.name})")
            }
            TrackingState.onCarDisconnected(System.currentTimeMillis())
        }
    }
}

/**
 * Receives car connect and disconnect signals on Android 12 and newer. The manifest only lets
 * senders holding the Bluetooth connect permission reach it, which is the phone's Bluetooth system.
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = CarBluetooth.onSignal(context, intent)
}

/** The same, for Android 8 to 11, where the phone's Bluetooth system holds the older permission. */
class CarBluetoothReceiverLegacy : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = CarBluetooth.onSignal(context, intent)
}
