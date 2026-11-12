package ru.fhnb16.btmanage.data

import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import ru.fhnb16.btmanage.elevated.requestLink
import ru.fhnb16.btmanage.service.Links
import ru.fhnb16.btmanage.service.parseCodecs

/** How an attempt to connect or disconnect a device ended. */
enum class LinkOutcome {
    /** Android took the request; the device follows, or does not, on its own. */
    ASKED,

    /** Android refuses ordinary applications, and neither Shizuku nor root is at hand. */
    NO_ACCESS,

    FAILED,
}

/**
 * Things done to a device rather than read from it.
 *
 * Android has no public call to connect or disconnect a paired device. What is tried, in
 * this order: the calls applications could use before Android 12; then the same request
 * made as the shell user through Shizuku, or as root.
 *
 * Written without a run on a device.
 */
object DeviceActions {
    private val codecMap = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The audio codec in use, by device address, for the devices it could be read for. */
    val codecs: StateFlow<Map<String, String>> = codecMap

    private var dumpedAt = 0L

    suspend fun link(context: Context, address: String, connect: Boolean): LinkOutcome = withContext(Dispatchers.IO) {
        val verb = if (connect) "connect" else "disconnect"
        val outcome = attempt(context.applicationContext, address, connect, verb)
        Diagnostics.lastAction = "$verb: ${outcome.second}"
        outcome.first
    }

    private suspend fun attempt(context: Context, address: String, connect: Boolean, verb: String): Pair<LinkOutcome, String> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return LinkOutcome.FAILED to "no Bluetooth adapter"

        // As an ordinary application.
        val refusal = try {
            requestLink(adapter, address, connect)
        } catch (error: Throwable) {
            error.javaClass.simpleName
        }
        if (refusal == null) return LinkOutcome.ASKED to "asked as an application"
        val onProfiles = try {
            Links.link(adapter.getRemoteDevice(address), connect)
        } catch (_: Throwable) {
            false
        }
        if (onProfiles) return LinkOutcome.ASKED to "asked on the profile services"

        // With borrowed rights. Shizuku is asked for its permission here, when it is needed.
        val way = when {
            Elevated.shizukuRunning() && Elevated.askShizuku() -> Elevated.Way.SHIZUKU
            Settings.current.useRoot -> Elevated.Way.ROOT
            else -> return LinkOutcome.NO_ACCESS to "refused ($refusal), no Shizuku or root"
        }
        val output = Elevated.run(way, Elevated.helper(context, verb, address))
            ?: return LinkOutcome.FAILED to "$way could not start the helper"
        return if (output.exit == 0) {
            LinkOutcome.ASKED to "asked through $way"
        } else {
            LinkOutcome.FAILED to "$way: ${output.text.takeLast(300)}"
        }
    }

    /**
     * Finds out the codec of the audio link to a device. Android tells ordinary applications
     * up to version 12; later it takes Shizuku or root, and only access that was already
     * granted is used: nobody is asked for anything because a page was opened.
     */
    suspend fun readCodec(context: Context, address: String) = withContext(Dispatchers.IO) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return@withContext
        var codec = try {
            Links.codec(adapter.getRemoteDevice(address))
        } catch (_: Throwable) {
            null
        }
        val way = Elevated.ready(Settings.current)
        val now = System.currentTimeMillis()
        if (codec == null && way != null && now - dumpedAt > DUMP_EVERY_MS) {
            dumpedAt = now
            val dump = Elevated.run(way, "dumpsys bluetooth_manager")
            if (dump != null && dump.exit == 0) codec = parseCodecs(dump.text)[address.takeLast(5).uppercase()]
        }
        if (codec != null) codecMap.value = codecMap.value + (address to codec)
    }

    private const val DUMP_EVERY_MS = 30_000L
}
