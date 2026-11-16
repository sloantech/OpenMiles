package org.openmiles.app.tracking

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A private test log of what drive detection did and why. It records times and events only,
 * never locations. It stays on the phone; the person can view it, share it, or clear it.
 */
object DiagLog {
    private const val FILE = "detection-log.txt"
    /** About 3,000 events. When full, the oldest half is dropped. */
    private const val MAX_BYTES = 200_000L
    private val timeFmt = DateTimeFormatter.ofPattern("MMM d HH:mm:ss", Locale.US)

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun add(context: Context, event: String) {
        try {
            val f = file(context)
            val clean = event.replace('\n', ' ').take(300)
            f.appendText("${LocalDateTime.now().format(timeFmt)}  $clean\n")
            if (f.length() > MAX_BYTES) {
                val lines = f.readLines()
                f.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
            }
        } catch (e: Exception) {
            // A log must never break the app.
        }
    }

    /** Newest events first. */
    @Synchronized
    fun read(context: Context, max: Int = 300): List<String> = try {
        val f = file(context)
        if (f.exists()) f.readLines().filter { it.isNotBlank() }.takeLast(max).reversed() else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    /** Phone and setup details that explain most missed drives. No personal data. */
    fun header(context: Context): String {
        val version = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "unknown"
        }
        val battery = try {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) "Unrestricted" else "Optimized (may block detection)"
        } catch (e: Exception) {
            "unknown"
        }
        val missing = AutoDetect.missingPermissions(context)
        return buildString {
            appendLine("OpenMiles detection log")
            appendLine("App version: $version")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            appendLine("Auto-detect: ${if (AutoDetect.isEnabled(context)) "on" else "off"}")
            appendLine("Missing permissions: ${if (missing.isEmpty()) "none" else missing.joinToString(", ")}")
            appendLine("Battery: $battery")
            appendLine("Newest events first. Times and events only, no locations.")
            appendLine()
        }
    }
}
