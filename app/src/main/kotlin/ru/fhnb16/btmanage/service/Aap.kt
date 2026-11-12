package ru.fhnb16.btmanage.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import btmanage.core.Native
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import ru.fhnb16.btmanage.data.Devices
import ru.fhnb16.btmanage.data.Diagnostics
import ru.fhnb16.btmanage.data.NoiseMode
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The channel AirPods keep for Apple's accessory protocol (AAP): an L2CAP channel of
 * Bluetooth Classic. Over it AirPods report their levels in steps of 1 %, and take the noise
 * mode and conversation awareness. The core builds and reads the packets
 * (`btmanage_core::apple::aap`); this object opens the channel and carries them.
 *
 * Android has no public way to open such a channel; a constructor of `BluetoothSocket`
 * outside the public interface does it, as in LibrePods. On most phones a fault in Android's
 * Bluetooth stack then keeps the channel from working: fixed on Pixel phones with Android 16
 * QPR3, and on recent OnePlus, OPPO and realme phones. Shizuku does not change that.
 *
 * Written without a run on a device.
 */
object Aap {
    enum class State { CONNECTING, READY, FAILED }

    /** How the channel to one device stands, and why it failed, in English, for the diagnostics. */
    data class Link(val state: State, val reason: String? = null)

    private val links = MutableStateFlow<Map<String, Link>>(emptyMap())

    /** By device address. A device without an entry has never been tried. */
    val state: StateFlow<Map<String, Link>> = links

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val triedAt = ConcurrentHashMap<String, Long>()

    /**
     * Keeps the channel open to a connected Apple accessory, in the background: levels and
     * settings then arrive on their own. A failed attempt is repeated a few times, at long
     * intervals, and then not until the device connects anew.
     */
    fun keep(context: Context, address: String) {
        if (sockets.containsKey(address) || links.value[address]?.state == State.CONNECTING) return
        val failed = failures[address] ?: 0
        val since = System.currentTimeMillis() - (triedAt[address] ?: 0L)
        if (failed >= MAX_QUIET_TRIES || (failed > 0 && since < RETRY_MS)) return
        val app = context.applicationContext
        scope.launch { open(app, address) }
    }

    /** The device went away: the channel went with it, and the next connection starts afresh. */
    fun forget(address: String) {
        sockets.remove(address)?.let { runCatching { it.close() } }
        failures.remove(address)
        links.value = links.value - address
    }

    /** Switches the noise mode. Null when the headphones took the command, else why not. */
    suspend fun setNoise(context: Context, address: String, mode: NoiseMode): String? =
        send(context, address, Native.aapPacket(COMMAND_NOISE, mode.code))

    /** Switches conversation awareness. Null when the headphones took the command, else why not. */
    suspend fun setAwareness(context: Context, address: String, on: Boolean): String? =
        send(context, address, Native.aapPacket(COMMAND_AWARENESS, if (on) 1 else 0))

    private suspend fun send(context: Context, address: String, hex: String?): String? {
        val packet = hex?.let(::bytes) ?: return "the core has no such packet"
        // The user asked for this: a channel that failed before is tried again at once.
        if (!sockets.containsKey(address)) {
            failures.remove(address)
            open(context.applicationContext, address)?.let { return it }
        }
        val socket = sockets[address] ?: return links.value[address]?.reason ?: "not connected"
        return withContext(Dispatchers.IO) {
            try {
                socket.outputStream.write(packet)
                socket.outputStream.flush()
                null
            } catch (error: IOException) {
                forget(address)
                "write failed: ${error.message}"
            }
        }
    }

    /** Opens the channel, says hello and asks for notifications. Null on success, else why not. */
    @SuppressLint("MissingPermission")
    private suspend fun open(context: Context, address: String): String? = withContext(Dispatchers.IO) {
        set(address, Link(State.CONNECTING))
        triedAt[address] = System.currentTimeMillis()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val failure = if (adapter == null) {
            "no Bluetooth adapter"
        } else {
            try {
                val socket = create(adapter, adapter.getRemoteDevice(address))
                // A socket that does not connect is closed after a while, which ends the wait.
                val watchdog = scope.launch {
                    delay(CONNECT_TIMEOUT_MS)
                    runCatching { socket.close() }
                }
                try {
                    socket.connect()
                } finally {
                    watchdog.cancel()
                }
                for (kind in intArrayOf(HELLO, FEATURES, NOTIFICATIONS)) {
                    val packet = Native.aapPacket(kind, 0)?.let(::bytes) ?: continue
                    socket.outputStream.write(packet)
                    socket.outputStream.flush()
                    delay(PACKET_GAP_MS)
                }
                sockets[address] = socket
                read(address, socket)
                null
            } catch (error: Throwable) {
                describe(error)
            }
        }
        if (failure == null) {
            failures.remove(address)
            set(address, Link(State.READY))
            Diagnostics.aap = "open to …${address.takeLast(5)}"
        } else {
            failures[address] = (failures[address] ?: 0) + 1
            set(address, Link(State.FAILED, failure))
            Diagnostics.aap = "failed: $failure"
        }
        failure
    }

    /**
     * Makes a socket for the channel, not yet connected. The constructor has changed between
     * versions of Android; each known form is tried in turn, newest first, as LibrePods does.
     */
    private fun create(adapter: BluetoothAdapter, device: BluetoothDevice): BluetoothSocket {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/BluetoothSocket;", "Landroid/bluetooth/BluetoothDevice;")
            } catch (_: Throwable) {
            }
        }
        val uuid = ParcelUuid.fromString(ACCESSORY_UUID)
        val forms = listOf(
            arrayOf<Any>(adapter, device, TYPE_L2CAP, true, true, PSM, uuid),
            arrayOf<Any>(device, TYPE_L2CAP, true, true, PSM, uuid),
            arrayOf<Any>(device, TYPE_L2CAP, 1, true, true, PSM, uuid),
            arrayOf<Any>(TYPE_L2CAP, 1, true, true, device, PSM, uuid),
            arrayOf<Any>(TYPE_L2CAP, true, true, device, PSM, uuid),
        )
        var last: Throwable? = null
        for (arguments in forms) {
            try {
                val types = arguments.map { it.javaClass.kotlin.javaPrimitiveType ?: it.javaClass }.toTypedArray()
                val constructor = BluetoothSocket::class.java.getDeclaredConstructor(*types)
                constructor.isAccessible = true
                return constructor.newInstance(*arguments) as BluetoothSocket
            } catch (error: Throwable) {
                last = error
            }
        }
        throw last ?: IllegalStateException("no constructor fits")
    }

    /** Hands every packet to the core until the channel closes. */
    private fun read(address: String, socket: BluetoothSocket) {
        Thread {
            val buffer = ByteArray(BUFFER)
            var packets = 0
            try {
                val input = socket.inputStream
                while (true) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    if (length == 0) continue
                    packets++
                    Diagnostics.aapPackets = packets
                    Devices.aap(address, buffer.copyOf(length))
                }
            } catch (_: IOException) {
            }
            if (sockets.remove(address, socket)) {
                runCatching { socket.close() }
                links.value = links.value - address
            }
        }.apply {
            isDaemon = true
            name = "aap-$address"
            start()
        }
    }

    private fun set(address: String, link: Link) {
        links.value = links.value + (address to link)
    }

    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        val message = cause.message
        return if (message.isNullOrEmpty()) cause.javaClass.simpleName else "${cause.javaClass.simpleName}: $message"
    }

    private fun bytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    /** The service Apple's accessories offer; LibrePods passes it with the channel. */
    const val ACCESSORY_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"

    private const val TYPE_L2CAP = 3
    private const val PSM = 0x1001
    private const val HELLO = 0
    private const val FEATURES = 1
    private const val NOTIFICATIONS = 2
    private const val COMMAND_NOISE = 3
    private const val COMMAND_AWARENESS = 4
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val PACKET_GAP_MS = 150L
    private const val RETRY_MS = 10 * 60 * 1000L
    private const val MAX_QUIET_TRIES = 3
    private const val BUFFER = 1024
}
