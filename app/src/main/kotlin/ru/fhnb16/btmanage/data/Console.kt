package ru.fhnb16.btmanage.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import ru.fhnb16.btmanage.service.Aap
import ru.fhnb16.btmanage.service.Links
import java.io.IOException
import java.util.UUID

/**
 * The developer console: raw traffic with a device over each way Android offers an
 * application. Everything sent and received goes into the console's lines and, with the
 * detailed log on, into the log file.
 *
 * - GATT, for Bluetooth LE devices: list the services, read, write, subscribe.
 * - RFCOMM, for serial ports of Bluetooth Classic (SPP and makers' own channels): send
 *   text or bytes, read what comes back.
 * - Hands-free: the one result code Android lets an application send to a headset.
 * - AAP, the protocol of AirPods, over the channel the application keeps open to them.
 *
 * Written without a run on a device.
 */
@SuppressLint("MissingPermission")
object Console {
    enum class Transport { GATT, RFCOMM, HEADSET, AAP }

    /** A command the user can start from: what it is for, where it goes, what it sends. */
    data class Template(
        val transport: Transport,
        val title: String,
        /** Text, or bytes as hexadecimal when [hex] is set. Empty for a read. */
        val payload: String,
        val hex: Boolean,
        /** The characteristic for GATT, the service UUID for RFCOMM. */
        val target: String? = null,
        /** For GATT: read the characteristic instead of writing to it. */
        val read: Boolean = false,
    )

    /** A characteristic found on a GATT server, and what it can do. */
    data class Characteristic(val service: UUID, val uuid: UUID, val properties: Int) {
        val canRead get() = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        val canWrite get() = properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val canNotify get() = properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }

    const val SPP = "00001101-0000-1000-8000-00805f9b34fb"
    private const val NUS_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val templates: List<Template> = listOf(
        Template(Transport.GATT, "Battery level", "", hex = false, target = short(0x2A19), read = true),
        Template(Transport.GATT, "Manufacturer", "", hex = false, target = short(0x2A29), read = true),
        Template(Transport.GATT, "Model", "", hex = false, target = short(0x2A24), read = true),
        Template(Transport.GATT, "Firmware", "", hex = false, target = short(0x2A26), read = true),
        Template(Transport.GATT, "Serial number", "", hex = false, target = short(0x2A25), read = true),
        Template(Transport.GATT, "Nordic UART: text", "Battery;", hex = false, target = NUS_RX),
        Template(Transport.RFCOMM, "AT", "AT\r\n", hex = false, target = SPP),
        Template(Transport.RFCOMM, "AT+VERSION?", "AT+VERSION?\r\n", hex = false, target = SPP),
        Template(Transport.RFCOMM, "AT+NAME?", "AT+NAME?\r\n", hex = false, target = SPP),
        Template(Transport.RFCOMM, "AT+BATT?", "AT+BATT?\r\n", hex = false, target = SPP),
        Template(Transport.RFCOMM, "AT+CBC", "AT+CBC\r\n", hex = false, target = SPP),
        Template(Transport.HEADSET, "+ANDROID", "test", hex = false),
        Template(Transport.AAP, "Ask for notifications", "04 00 04 00 0F 00 FF FF FF FF", hex = true),
        Template(Transport.AAP, "Noise cancellation", "04 00 04 00 09 00 0D 02 00 00 00", hex = true),
        Template(Transport.AAP, "Transparency", "04 00 04 00 09 00 0D 03 00 00 00", hex = true),
        Template(Transport.AAP, "Noise control off", "04 00 04 00 09 00 0D 01 00 00 00", hex = true),
    )

    private val found = MutableStateFlow<List<Characteristic>>(emptyList())

    /** The characteristics of the GATT server the console is connected to. */
    val characteristics: StateFlow<List<Characteristic>> = found

    private val state = MutableStateFlow("")

    /** What the console is connected to, in words; empty when nothing. */
    val connection: StateFlow<String> = state

    private var gatt: BluetoothGatt? = null
    private var socket: BluetoothSocket? = null

    fun note(text: String) = EventLog.note("console", text)

    /** Bytes from a hexadecimal string ("04 00 0f", "04000F"), or null if it is not one. */
    fun parseHex(text: String): ByteArray? {
        val digits = text.filter { !it.isWhitespace() && it != ':' && it != ',' }.removePrefix("0x")
        if (digits.isEmpty() || digits.length % 2 != 0) return null
        return try {
            ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** What to send: the text as UTF-8, or the bytes it spells in hexadecimal. */
    fun payload(text: String, hex: Boolean): ByteArray? = if (hex) parseHex(text) else text.toByteArray()

    private fun device(context: Context, address: String): BluetoothDevice? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)

    // --- GATT ---------------------------------------------------------------------------

    fun connectGatt(context: Context, address: String) {
        closeAll()
        val device = device(context, address) ?: return note("no Bluetooth adapter")
        note("GATT: connecting to $address")
        state.value = "GATT $address: connecting"
        gatt = try {
            device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (error: Throwable) {
            note("GATT: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    private fun find(uuid: UUID): BluetoothGattCharacteristic? {
        val server = gatt ?: return null
        return server.services.firstNotNullOfOrNull { it.getCharacteristic(uuid) }
    }

    @Suppress("DEPRECATION")
    fun read(uuid: UUID) {
        val server = gatt ?: return note("GATT: not connected")
        val characteristic = find(uuid) ?: return note("GATT: no characteristic $uuid")
        note("GATT read $uuid: " + if (server.readCharacteristic(characteristic)) "asked" else "refused")
    }

    @Suppress("DEPRECATION")
    fun write(uuid: UUID, bytes: ByteArray) {
        val server = gatt ?: return note("GATT: not connected")
        val characteristic = find(uuid) ?: return note("GATT: no characteristic $uuid")
        val noResponse = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.writeCharacteristic(characteristic, bytes, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.writeType = type
            characteristic.value = bytes
            server.writeCharacteristic(characteristic)
        }
        note("GATT write $uuid -> ${EventLog.hex(bytes)}: " + if (sent) "sent" else "refused")
    }

    @Suppress("DEPRECATION")
    fun subscribe(uuid: UUID) {
        val server = gatt ?: return note("GATT: not connected")
        val characteristic = find(uuid) ?: return note("GATT: no characteristic $uuid")
        server.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD) ?: return note("GATT: $uuid has no notification switch")
        val indicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            descriptor.value = value
            server.writeDescriptor(descriptor)
        }
        note("GATT subscribe $uuid: " + if (sent) "asked" else "refused")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            note("GATT ${gatt.device.address}: ${if (connected) "connected" else "disconnected"}, status $status")
            state.value = "GATT ${gatt.device.address}: " + if (connected) "connected" else "disconnected"
            if (connected) {
                gatt.discoverServices()
            } else {
                found.value = emptyList()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val list = gatt.services.flatMap { service ->
                service.characteristics.map { Characteristic(service.uuid, it.uuid, it.properties) }
            }
            found.value = list
            note("GATT: ${gatt.services.size} services, ${list.size} characteristics, status $status")
            for (service in gatt.services) {
                note("  service ${name(service.uuid)}")
                for (characteristic in service.characteristics) {
                    note("    ${name(characteristic.uuid)} ${properties(characteristic.properties)}")
                }
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            received("read", characteristic.uuid, characteristic.value, status)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            received("notified", characteristic.uuid, characteristic.value, BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            note("GATT write ${name(characteristic.uuid)}: status $status")
        }
    }

    private fun received(what: String, uuid: UUID, value: ByteArray?, status: Int) {
        val bytes = value ?: ByteArray(0)
        val text = bytes.toString(Charsets.UTF_8).filter { it >= ' ' }
        note("GATT $what ${name(uuid)}, status $status: ${EventLog.hex(bytes)}  \"$text\"")
    }

    // --- RFCOMM -------------------------------------------------------------------------

    /** Opens a serial channel to the service [uuid] of a device. */
    suspend fun connectRfcomm(context: Context, address: String, uuid: UUID, secure: Boolean) = withContext(Dispatchers.IO) {
        closeAll()
        val device = device(context, address) ?: return@withContext note("no Bluetooth adapter")
        note("RFCOMM: connecting to $address, $uuid, ${if (secure) "secure" else "insecure"}")
        state.value = "RFCOMM $address: connecting"
        val opened = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery()
            val channel = if (secure) device.createRfcommSocketToServiceRecord(uuid) else device.createInsecureRfcommSocketToServiceRecord(uuid)
            channel.connect()
            channel
        } catch (error: IOException) {
            note("RFCOMM: ${error.message}")
            state.value = ""
            null
        }
        if (opened != null) {
            socket = opened
            state.value = "RFCOMM $address: connected"
            note("RFCOMM: connected")
            Thread {
                val buffer = ByteArray(1024)
                try {
                    while (true) {
                        val length = opened.inputStream.read(buffer)
                        if (length < 0) break
                        val bytes = buffer.copyOf(length)
                        note("RFCOMM <- ${EventLog.hex(bytes)}  \"${bytes.toString(Charsets.UTF_8).filter { it >= ' ' }}\"")
                    }
                } catch (_: IOException) {
                }
                note("RFCOMM: closed")
                if (socket === opened) state.value = ""
            }.apply {
                isDaemon = true
                start()
            }
        }
    }

    suspend fun sendRfcomm(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val open = socket ?: return@withContext note("RFCOMM: not connected")
        try {
            open.outputStream.write(bytes)
            open.outputStream.flush()
            note("RFCOMM -> ${EventLog.hex(bytes)}")
        } catch (error: IOException) {
            note("RFCOMM: ${error.message}")
        }
    }

    // --- Hands-free and AAP ---------------------------------------------------------------

    /**
     * Sends "+ANDROID: [argument]" to a connected headset over its hands-free link. Android
     * lets applications send this one result code and no other.
     */
    fun sendHeadset(context: Context, address: String, argument: String) {
        val device = device(context, address) ?: return note("no Bluetooth adapter")
        val headset = Links.proxy(BluetoothProfile.HEADSET) as? BluetoothHeadset
            ?: return note("hands-free: Android has not handed over its headset service")
        val sent = try {
            headset.sendVendorSpecificResultCode(device, BluetoothHeadset.VENDOR_RESULT_CODE_COMMAND_ANDROID, argument)
        } catch (error: Throwable) {
            note("hands-free: ${error.javaClass.simpleName}: ${error.message}")
            return
        }
        note("hands-free -> +ANDROID: $argument: " + if (sent) "sent" else "refused")
    }

    suspend fun sendAap(context: Context, address: String, bytes: ByteArray) {
        note("AAP -> ${EventLog.hex(bytes)}")
        val failure = Aap.sendRaw(context, address, bytes)
        if (failure != null) note("AAP: $failure")
    }

    /** Closes whatever the console has open. */
    fun closeAll() {
        gatt?.let {
            runCatching {
                it.disconnect()
                it.close()
            }
        }
        gatt = null
        found.value = emptyList()
        socket?.let { runCatching { it.close() } }
        socket = null
        state.value = ""
    }

    private fun short(value: Int) = "0000%04x-0000-1000-8000-00805f9b34fb".format(value)

    /** "2A19" for a UUID on the Bluetooth base, the whole UUID for any other. */
    fun name(uuid: UUID): String {
        val text = uuid.toString()
        return if (text.endsWith("-0000-1000-8000-00805f9b34fb") && text.startsWith("0000")) text.substring(4, 8).uppercase() else text
    }

    private fun properties(value: Int): String = listOfNotNull(
        "read".takeIf { value and BluetoothGattCharacteristic.PROPERTY_READ != 0 },
        "write".takeIf { value and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 },
        "write-no-response".takeIf { value and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 },
        "notify".takeIf { value and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 },
        "indicate".takeIf { value and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 },
    ).joinToString(", ", "[", "]")

    /** Turns "2A19" or a whole UUID into a UUID, or null. */
    fun uuid(text: String): UUID? {
        val clean = text.trim()
        return try {
            if (clean.length == 4) UUID.fromString(short(clean.toInt(16))) else UUID.fromString(clean)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
