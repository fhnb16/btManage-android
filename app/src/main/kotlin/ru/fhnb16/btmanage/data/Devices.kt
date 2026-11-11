package ru.fhnb16.btmanage.data

import android.content.Context
import btmanage.core.Native
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Role { LEFT, RIGHT, CASE, MAIN }

/** One battery: an earbud, a case, or the single battery of a device. */
data class Cell(
    val role: Role,
    val percent: Int,
    val charging: Boolean,
    val inEar: Boolean?,
    val ageMs: Long,
    val source: String,
) {
    /** A level older than two minutes is remembered rather than current. */
    val isFresh: Boolean get() = ageMs < 2 * 60_000

    fun isLow(threshold: Int): Boolean = percent <= threshold && !charging
}

data class Device(
    val id: String,
    /** "system", "apple" or "fast_pair". */
    val origin: String,
    val name: String,
    val model: String?,
    /** The core's kind in lower case: "earbuds", "mouse" and so on. */
    val kind: String,
    val address: String?,
    val transport: String?,
    val paired: Boolean,
    val connected: Boolean,
    val nearby: Boolean,
    val cells: List<Cell>,
    val rssi: Int?,
    val estimateMin: Int?,
    val profiles: List<String>,
) {
    val isActive: Boolean get() = connected || nearby
    val hasEarbudCells: Boolean get() = cells.any { it.role == Role.LEFT || it.role == Role.RIGHT }

    /** The level that runs out first, the case aside. */
    val lowestCell: Cell? get() = cells.filter { it.role != Role.CASE }.minByOrNull { it.percent }

    fun cell(role: Role): Cell? = cells.firstOrNull { it.role == role }
}

data class HistoryPoint(val t: Long, val percent: Int, val charging: Boolean)

data class RoleHistory(val role: Role, val points: List<HistoryPoint>)

/**
 * The device list of the application, kept by the Rust core. The scanning service feeds it;
 * the screens and the widgets read [list]. What the core remembers (levels and their
 * history) is stored in a file between runs, and the last list is cached so that a widget
 * has something to show before the service is up.
 */
object Devices {
    const val ADV_MANUFACTURER = 0
    const val ADV_SERVICE_DATA = 1

    private const val STATE_FILE = "state.json"
    private const val SNAPSHOT_FILE = "snapshot.json"

    private val devices = MutableStateFlow<List<Device>>(emptyList())
    val list: StateFlow<List<Device>> = devices

    private var hub = 0L
    private var filesDir: File? = null

    /** Whether the core library is present; without it there is nothing to show. */
    val available: Boolean get() = Native.available

    @Synchronized
    fun open(context: Context) {
        val dir = context.applicationContext.filesDir
        filesDir = dir
        if (devices.value.isEmpty()) {
            // The list as it was when the application last ran, until fresh data arrives.
            runCatching { File(dir, SNAPSHOT_FILE).readText() }.getOrNull()?.let { cached ->
                runCatching { devices.value = parse(cached).map { it.copy(connected = false, nearby = false) } }
            }
        }
        if (hub == 0L && Native.available) {
            hub = Native.hubNew()
            runCatching { File(dir, STATE_FILE).readText() }.getOrNull()?.let { Native.hubImport(hub, it) }
        }
    }

    fun advertisement(address: Long, rssi: Int, kind: Int, id: Int, data: ByteArray) =
        publish(call { Native.hubAdvertisement(it, address, rssi, kind, id, data, now()) })

    fun setSystemDevices(devicesJson: String) =
        publish(call { Native.hubSetSystemDevices(it, devicesJson, now()) })

    /** Re-reads the list, so ages move on and devices that left drop out. */
    fun refresh() = publish(call { Native.hubSnapshot(it, now()) })

    fun history(deviceId: String, windowMs: Long): List<RoleHistory> {
        val json = call { Native.hubHistory(it, deviceId, now() - windowMs) } ?: return emptyList()
        return runCatching {
            val series = JSONArray(json)
            List(series.length()) { index ->
                val entry = series.getJSONObject(index)
                val points = entry.getJSONArray("points")
                RoleHistory(
                    role = role(entry.getString("role")),
                    points = List(points.length()) { i ->
                        val point = points.getJSONObject(i)
                        HistoryPoint(point.getLong("t"), point.getInt("percent"), point.getBoolean("charging"))
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun forget(deviceId: String) {
        call { Native.hubForget(it, deviceId); null }
        refresh()
    }

    /** Writes what the core remembers and the current list to disk. */
    fun save() {
        val dir = filesDir ?: return
        call { Native.hubExport(it) }?.let { state -> runCatching { File(dir, STATE_FILE).writeText(state) } }
        runCatching { File(dir, SNAPSHOT_FILE).writeText(lastJson ?: return) }
    }

    @Volatile
    private var lastJson: String? = null

    private fun now() = System.currentTimeMillis()

    private inline fun call(block: (Long) -> String?): String? {
        val handle = hub
        return if (handle == 0L) null else block(handle)
    }

    private fun publish(json: String?) {
        json ?: return
        runCatching { parse(json) }.onSuccess {
            lastJson = json
            devices.value = it
        }
    }

    private fun parse(json: String): List<Device> {
        val array = JSONObject(json).getJSONArray("devices")
        return List(array.length()) { index ->
            val device = array.getJSONObject(index)
            val cells = device.getJSONArray("cells")
            val profiles = device.getJSONArray("profiles")
            Device(
                id = device.getString("id"),
                origin = device.getString("origin"),
                name = device.getString("name"),
                model = device.stringOrNull("model"),
                kind = device.getString("kind"),
                address = device.stringOrNull("address"),
                transport = device.stringOrNull("transport"),
                paired = device.getBoolean("paired"),
                connected = device.getBoolean("connected"),
                nearby = device.getBoolean("nearby"),
                cells = List(cells.length()) { i ->
                    val cell = cells.getJSONObject(i)
                    Cell(
                        role = role(cell.getString("role")),
                        percent = cell.getInt("percent"),
                        charging = cell.getBoolean("charging"),
                        inEar = if (cell.has("in_ear")) cell.getBoolean("in_ear") else null,
                        ageMs = cell.getLong("age_ms"),
                        source = cell.getString("source"),
                    )
                },
                rssi = if (device.isNull("rssi")) null else device.getInt("rssi"),
                estimateMin = if (device.isNull("estimate_min")) null else device.getInt("estimate_min"),
                profiles = List(profiles.length()) { i -> profiles.getString(i) },
            )
        }
    }

    private fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else getString(name)

    private fun role(name: String): Role = when (name) {
        "left" -> Role.LEFT
        "right" -> Role.RIGHT
        "case" -> Role.CASE
        else -> Role.MAIN
    }
}
