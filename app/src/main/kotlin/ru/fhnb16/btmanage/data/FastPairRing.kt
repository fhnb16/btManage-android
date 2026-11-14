package ru.fhnb16.btmanage.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Makes Fast Pair earbuds ring, through the message stream of Fast Pair: an RFCOMM channel
 * every Fast Pair device offers, on which "ring" is a device action (message group 4,
 * code 1). This is what "Find my device" does with earbuds.
 *
 * Written from Google's description of the message stream, without a run on a device.
 * Google's own Fast Pair service may hold the channel already; then the device refuses a
 * second one, and the answer says so.
 */
object FastPairRing {
    /** The service of the message stream; a device that lists it can ring. */
    val STREAM: UUID = UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c")

    private const val GROUP_DEVICE_ACTION: Byte = 0x04
    private const val CODE_RING: Byte = 0x01
    private const val BOTH: Byte = 0x03
    private const val STOP: Byte = 0x00

    /** Whether a paired device offers the message stream. */
    @SuppressLint("MissingPermission")
    fun offers(context: Context, address: String): Boolean = try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        adapter?.getRemoteDevice(address)?.uuids?.any { it.uuid == STREAM } == true
    } catch (_: Throwable) {
        false
    }

    /** Rings both earbuds, or stops them ([on] false). Null when the device took it, else why not. */
    @SuppressLint("MissingPermission")
    suspend fun ring(context: Context, address: String, on: Boolean): String? = withContext(Dispatchers.IO) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return@withContext "no Bluetooth adapter"
        val message = byteArrayOf(GROUP_DEVICE_ACTION, CODE_RING, 0x00, 0x01, if (on) BOTH else STOP)
        try {
            adapter.cancelDiscovery()
            adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(STREAM).use { socket ->
                socket.connect()
                socket.outputStream.write(message)
                socket.outputStream.flush()
                // Leave the device a moment to read it before the channel closes.
                delay(800)
            }
            EventLog.note("action", "fast pair ring $address ${if (on) "on" else "off"}: sent")
            null
        } catch (error: IOException) {
            EventLog.note("action", "fast pair ring $address: ${error.message}")
            error.message ?: error.javaClass.simpleName
        } catch (error: SecurityException) {
            error.message ?: error.javaClass.simpleName
        }
    }
}
