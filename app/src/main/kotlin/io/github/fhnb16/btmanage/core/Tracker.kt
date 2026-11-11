package io.github.fhnb16.btmanage.core

import android.os.SystemClock
import btmanage.core.Native
import org.json.JSONObject

/** One battery: an earbud, a case, or a whole pair of headphones. */
data class Cell(val percent: Int, val charging: Boolean, val inEar: Boolean?) {
    /** At or below 20 %, and not charging. */
    val isLow: Boolean get() = percent <= 20 && !charging
}

data class Device(
    val id: String,
    val model: String?,
    val isHeadphones: Boolean,
    val left: Cell?,
    val right: Cell?,
    val case: Cell?,
    val main: Cell?,
    val ageMs: Long,
)

/** The device tracker of the core. Nearest device first in every snapshot. */
class Tracker : AutoCloseable {
    private var handle = Native.trackerNew()

    /** Feeds the manufacturer data of one advertisement. */
    fun ingest(address: Long, rssi: Int, companyId: Int, data: ByteArray) {
        Native.trackerIngest(handle, address, rssi, companyId, data, SystemClock.elapsedRealtime())
    }

    fun snapshot(): List<Device> =
        Native.trackerSnapshot(handle, SystemClock.elapsedRealtime())?.let(::parse).orEmpty()

    override fun close() {
        if (handle != 0L) {
            Native.trackerFree(handle)
            handle = 0L
        }
    }

    private fun parse(json: String): List<Device> {
        val devices = JSONObject(json).getJSONArray("devices")
        return List(devices.length()) { index ->
            val device = devices.getJSONObject(index)
            Device(
                id = device.getString("id"),
                model = if (device.isNull("model")) null else device.getString("model"),
                isHeadphones = device.getString("form") == "headphones",
                left = device.cell("left"),
                right = device.cell("right"),
                case = device.cell("case"),
                main = device.cell("main"),
                ageMs = device.getLong("age_ms"),
            )
        }
    }

    private fun JSONObject.cell(name: String): Cell? {
        val cell = optJSONObject(name) ?: return null
        return Cell(
            percent = cell.getInt("percent"),
            charging = cell.getBoolean("charging"),
            inEar = if (cell.has("in_ear")) cell.getBoolean("in_ear") else null,
        )
    }
}
