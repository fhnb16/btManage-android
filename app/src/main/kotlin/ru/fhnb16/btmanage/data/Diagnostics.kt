package ru.fhnb16.btmanage.data

import android.os.Build
import btmanage.core.Native
import ru.fhnb16.btmanage.BuildConfig
import java.util.concurrent.ConcurrentHashMap
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

    /** Messages of Apple devices among the advertisements, by type. 07 carries the levels. */
    val appleTypes = ConcurrentHashMap<Int, AtomicInteger>()

    /** The start of the last message with levels, and how loud it was. */
    @Volatile
    var lastProximity = "none"

    /** The paired devices as they were last described to the core, a line each. */
    @Volatile
    var pairedDevices = ""

    /** How the last attempt at the channel of Apple's accessory protocol went. */
    @Volatile
    var aap = "not tried"

    @Volatile
    var aapPackets = 0

    /** How the last read of Android's own record of a device went: it takes Shizuku or root. */
    @Volatile
    var metadata = "not tried"

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
        val types = appleTypes.toSortedMap().entries
            .joinToString(", ") { "%02X x%d".format(it.key, it.value.get()) }
            .ifEmpty { "none" }
        val list = Devices.list.value
        val withLevels = list.count { it.cells.isNotEmpty() }
        val fromAdverts = list.count { device -> device.cells.any { it.source == "advert" } }
        return listOf(
            "btManage ${BuildConfig.VERSION_NAME}, core ${if (Native.available) Native.version() else "missing"}",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            "scan: $scan, started ${scanStarts.get()} times",
            "advertisements: ${adverts.get()} total, ${appleAdverts.get()} Apple, ${fastPairAdverts.get()} Fast Pair, last $ago",
            "Apple message types: $types",
            "last message with levels: $lastProximity",
            "list: ${list.size} devices, $withLevels with levels, $fromAdverts from advertisements",
            "paired: $paired, connected: $connected",
            pairedDevices.ifEmpty { "  (none read yet)" },
            "isConnected: $connectedCall, getBatteryLevel: $batteryCall",
            "profile services: ${profiles.ifEmpty { "none" }}",
            "last level event: $lastLevel",
            "Shizuku: ${if (Elevated.shizukuRunning()) (if (Elevated.shizukuGranted()) "allowed" else "running") else "not running"}",
            "accessory protocol: $aap, $aapPackets packets",
            "Android's record: $metadata",
            "last action: $lastAction",
        ).joinToString("\n")
    }
}
