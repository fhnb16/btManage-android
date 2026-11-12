package ru.fhnb16.btmanage.data

import android.os.Build
import btmanage.core.Native
import ru.fhnb16.btmanage.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the watching service has seen and which of Android's calls answered. Shown in the
 * settings so that a report of "no battery level" comes with the reason.
 */
object Diagnostics {
    /** State of the advertisement scan, in words. */
    @Volatile
    var scan = "not started"

    val scanStarts = AtomicInteger()

    /** Advertisements delivered, of any kind. */
    val adverts = AtomicInteger()

    /** Apple proximity pairing messages among them. */
    val appleAdverts = AtomicInteger()

    val fastPairAdverts = AtomicInteger()

    @Volatile
    var lastAdvertAt = 0L

    /** Whether `BluetoothDevice.isConnected` and `getBatteryLevel` could be called. */
    @Volatile
    var connectedCall = "not tried"

    @Volatile
    var batteryCall = "not tried"

    /** The profile services Android handed over, by profile number. */
    @Volatile
    var profiles = ""

    @Volatile
    var paired = 0

    @Volatile
    var connected = 0

    /** The last level that arrived on its own: a broadcast, a headset event, a GATT read. */
    @Volatile
    var lastLevel = "none"

    /** The last connect or disconnect and how it went. */
    @Volatile
    var lastAction = "none"

    fun report(): String {
        val ago = if (lastAdvertAt == 0L) "never" else "${(System.currentTimeMillis() - lastAdvertAt) / 1000} s ago"
        return listOf(
            "btManage ${BuildConfig.VERSION_NAME}, core ${if (Native.available) Native.version() else "missing"}",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            "scan: $scan, started ${scanStarts.get()} times",
            "advertisements: ${adverts.get()} total, ${appleAdverts.get()} Apple, ${fastPairAdverts.get()} Fast Pair, last $ago",
            "paired: $paired, connected: $connected",
            "isConnected: $connectedCall, getBatteryLevel: $batteryCall",
            "profile services: ${profiles.ifEmpty { "none" }}",
            "last level event: $lastLevel",
            "Shizuku: ${if (Elevated.shizukuRunning()) (if (Elevated.shizukuGranted()) "allowed" else "running") else "not running"}",
            "last action: $lastAction",
        ).joinToString("\n")
    }
}
