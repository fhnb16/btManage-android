package ru.fhnb16.btmanage.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** What a device says about itself beyond its levels. An unknown fact is null. */
data class Facts(
    val manufacturer: String? = null,
    val model: String? = null,
    val firmware: String? = null,
)

/**
 * What Android itself has on record for the paired devices: the levels of single earbuds
 * and of the case, the maker, the model, the firmware. Headphones of many makers land
 * there through Fast Pair, which is why Android's own Bluetooth settings can show three
 * levels for them. Android lets only privileged callers read the record, so this works
 * with Shizuku or root, and only with access the user has already given.
 *
 * Written without a run on a device.
 */
object DeviceFacts {
    private val map = MutableStateFlow<Map<String, Facts>>(emptyMap())

    /** By device address. */
    val facts: StateFlow<Map<String, Facts>> = map

    private val busy = AtomicBoolean()

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val way = Elevated.ready(Settings.current)
        if (way == null) {
            Diagnostics.metadata = "needs Shizuku or root"
            return@withContext
        }
        if (!busy.compareAndSet(false, true)) return@withContext
        try {
            val output = Elevated.run(way, Elevated.helper(context.applicationContext, "metadata"))
            if (output == null) {
                Diagnostics.metadata = "$way could not start the helper"
                return@withContext
            }
            val records = parseMetadata(output.text)
            val connected = Devices.list.value.filter { it.connected }.map { it.id }.toSet()
            var withLevels = 0
            for ((address, record) in records) {
                // A level on record for a device that is away is of unknown age: leave it.
                if (address in connected && feedLevels(address, record)) withLevels++
            }
            map.value = records.mapValues { (_, record) ->
                Facts(manufacturer = record[0], model = record[1], firmware = record[2])
            }.filterValues { it != Facts() }
            val verdict = if (output.exit == 0) "" else ", " + output.text.lineSequence().lastOrNull().orEmpty().take(200)
            Diagnostics.metadata = "$way, ${records.size} devices, $withLevels with levels$verdict"
        } finally {
            busy.set(false)
        }
    }

    /** Hands the levels of a record to the core. True when there was at least one. */
    private fun feedLevels(address: String, record: Map<Int, String>): Boolean {
        var any = false
        for ((role, level, charging) in CELLS) {
            val percent = record[level]?.toIntOrNull() ?: continue
            if (percent !in 0..100) continue
            Devices.cell(address, role, Devices.SOURCE_SYSTEM, percent, record[charging].equals("true", ignoreCase = true))
            any = true
        }
        return any
    }

    /** The battery, the key of its level and the key of its charging state. */
    private val CELLS = listOf(
        Triple(Role.LEFT, 10, 13),
        Triple(Role.RIGHT, 11, 14),
        Triple(Role.CASE, 12, 15),
        Triple(Role.MAIN, 18, 19),
    )
}

/**
 * Reads the helper's answer to `metadata`: lines of `device <address>|<key>=<value>|...`.
 * Gives the values by key, by device address.
 */
internal fun parseMetadata(text: String): Map<String, Map<Int, String>> {
    val records = HashMap<String, Map<Int, String>>()
    for (line in text.lineSequence()) {
        if (!line.startsWith("device ")) continue
        val parts = line.removePrefix("device ").split('|')
        val address = parts.first().trim().uppercase()
        val values = HashMap<Int, String>()
        for (part in parts.drop(1)) {
            val key = part.substringBefore('=').toIntOrNull() ?: continue
            val value = part.substringAfter('=', "").trim()
            if (value.isNotEmpty()) values[key] = value
        }
        records[address] = values
    }
    return records
}
