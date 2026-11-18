package ru.fhnb16.btmanage.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
 * - What Android knows of a device without connecting: kind, class, services from SDP,
 *   profiles, battery.
 * - GATT, for Bluetooth LE devices: every service, characteristic and descriptor with its
 *   name, read one or all, write, subscribe. Android takes one GATT request at a time; the
 *   console queues them.
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
    private const val NUS_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private const val HUAMI_BATTERY = "00000006-0000-3512-2118-0009af100700"
    private const val FAST_PAIR_STREAM = "df21fe2c-2515-4fdb-8886-f12c4d67927c"
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** A GATT request that waits longer than this for its answer no longer holds up the others. */
    private const val REQUEST_TIMEOUT_MS = 6_000L

    val templates: List<Template> = listOf(
        Template(Transport.GATT, "Battery level", "", hex = false, target = short(0x2A19), read = true),
        Template(Transport.GATT, "Device name", "", hex = false, target = short(0x2A00), read = true),
        Template(Transport.GATT, "Manufacturer", "", hex = false, target = short(0x2A29), read = true),
        Template(Transport.GATT, "Model", "", hex = false, target = short(0x2A24), read = true),
        Template(Transport.GATT, "Firmware", "", hex = false, target = short(0x2A26), read = true),
        Template(Transport.GATT, "Hardware", "", hex = false, target = short(0x2A27), read = true),
        Template(Transport.GATT, "Serial number", "", hex = false, target = short(0x2A25), read = true),
        Template(Transport.GATT, "PnP ID: vendor and product", "", hex = false, target = short(0x2A50), read = true),
        Template(Transport.GATT, "Heart rate", "", hex = false, target = short(0x2A37)),
        Template(Transport.GATT, "Huami band: battery", "", hex = false, target = HUAMI_BATTERY, read = true),
        Template(Transport.GATT, "Immediate alert: ring", "02", hex = true, target = short(0x2A06)),
        Template(Transport.GATT, "Nordic UART: text", "Battery;", hex = false, target = NUS_RX),
        Template(Transport.GATT, "Nordic UART: listen", "", hex = false, target = NUS_TX),
        Template(Transport.RFCOMM, "AT", "AT\r\n", hex = false, target = SPP),
        Template(Transport.RFCOMM, "AT+VERSION?", "AT+VERSION?\r\n", hex = false, target = SPP),
        Template(Transport.RFCOMM, "AT+NAME?", "AT+NAME?\r\n", hex = false, target = SPP),
        Template(Transport.RFCOMM, "AT+BATT?", "AT+BATT?\r\n", hex = false, target = SPP),
        Template(Transport.RFCOMM, "AT+CBC", "AT+CBC\r\n", hex = false, target = SPP),
        Template(Transport.RFCOMM, "Fast Pair: ring", "04 01 00 01 03", hex = true, target = FAST_PAIR_STREAM),
        Template(Transport.HEADSET, "+ANDROID", "test", hex = false),
        Template(Transport.AAP, "Ask for notifications", "04 00 04 00 0F 00 FF FF FF FF", hex = true),
        Template(Transport.AAP, "Noise cancellation", "04 00 04 00 09 00 0D 02 00 00 00", hex = true),
        Template(Transport.AAP, "Transparency", "04 00 04 00 09 00 0D 03 00 00 00", hex = true),
        Template(Transport.AAP, "Adaptive", "04 00 04 00 09 00 0D 04 00 00 00", hex = true),
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
    private val main = Handler(Looper.getMainLooper())

    fun note(text: String) = EventLog.note("console", text)

    /** Bytes from a hexadecimal string ("04 00 0f", "04000F"), or null if it is not one. */
    fun parseHex(text: String): ByteArray? {
        val digits = text.filter { !it.isWhitespace() && it != ':' && it != ',' }.replace("0x", "").replace("0X", "")
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

    // --- What Android knows ---------------------------------------------------------------

    /**
     * Everything Android tells an application about a device without connecting to it, and
     * a fresh SDP query of its Bluetooth Classic services, whose answer follows in a moment.
     */
    fun about(context: Context, address: String) {
        val device = device(context, address) ?: return note("no Bluetooth adapter")
        note("About $address")
        val type = when (device.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Bluetooth Classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "Bluetooth LE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "dual mode"
            else -> "unknown"
        }
        val bond = when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> "paired"
            BluetoothDevice.BOND_BONDING -> "pairing"
            else -> "not paired"
        }
        note("  name: ${device.name ?: "?"}" + (alias(device)?.let { ", alias: $it" } ?: ""))
        note("  type: $type, $bond")
        device.bluetoothClass?.let { cls ->
            note("  class: 0x%06X, major 0x%04X, device 0x%04X%s".format(
                classBits(cls),
                cls.majorDeviceClass,
                cls.deviceClass,
                if (cls.hasService(BluetoothClass.Service.AUDIO)) ", audio" else "",
            ))
        }
        hidden(device, "isConnected")?.let { note("  connected: $it") }
        hidden(device, "getBatteryLevel")?.let { level -> if (level is Int && level >= 0) note("  battery Android keeps: $level %") }
        val profiles = listOf(1 to "hands-free", 2 to "A2DP", 4 to "input", 21 to "hearing aid", 22 to "LE Audio")
            .mapNotNull { (id, name) ->
                val proxy = Links.proxy(id) ?: return@mapNotNull null
                val connected = runCatching { proxy.getConnectionState(device) }.getOrNull() ?: return@mapNotNull null
                "$name ${stateName(connected)}"
            }
        if (profiles.isNotEmpty()) note("  profiles: ${profiles.joinToString(", ")}")
        device.uuids?.let { uuids ->
            note("  services known: ${uuids.size}")
            for (uuid in uuids) note("    ${GattNames.describe(uuid.uuid)}")
        }
        querySdp(context.applicationContext, device)
    }

    /** Asks the device for its services over SDP; the answer comes as a broadcast. */
    private fun querySdp(context: Context, device: BluetoothDevice) {
        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val from = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (from?.address != device.address) return
                val uuids = IntentCompat.getParcelableArrayExtra(intent, BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                note("SDP ${device.address}: ${uuids?.size ?: 0} services")
                uuids?.forEach { note("    ${GattNames.describe((it as ParcelUuid).uuid)}") }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_UUID), ContextCompat.RECEIVER_EXPORTED)
            note(if (device.fetchUuidsWithSdp()) "SDP: asked" else "SDP: refused")
            main.postDelayed({ runCatching { context.unregisterReceiver(receiver) } }, SDP_WAIT_MS)
        }.onFailure { note("SDP: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private const val SDP_WAIT_MS = 15_000L

    private fun alias(device: BluetoothDevice): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { device.alias }.getOrNull() else null

    private fun classBits(cls: BluetoothClass): Int = runCatching {
        BluetoothClass::class.java.getMethod("getClassOfDevice").invoke(cls) as Int
    }.getOrDefault(cls.deviceClass)

    /** A call Android keeps out of its public interface, or null when it is not there. */
    private fun hidden(device: BluetoothDevice, method: String): Any? =
        runCatching { BluetoothDevice::class.java.getMethod(method).invoke(device) }.getOrNull()

    private fun stateName(state: Int) = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "not connected"
    }

    // --- GATT ---------------------------------------------------------------------------

    /** Requests waiting for their turn: each returns whether Android took it. */
    private val queue = ArrayDeque<() -> Boolean>()
    private var busySince = 0L

    /** Queues a GATT request; Android takes the next once the answer to this one came. */
    private fun enqueue(request: () -> Boolean) {
        synchronized(queue) {
            queue.addLast(request)
            if (busySince != 0L && System.currentTimeMillis() - busySince > REQUEST_TIMEOUT_MS) busySince = 0L
            if (busySince == 0L) next()
        }
    }

    /** The answer to the request in flight came: on to the next. */
    private fun next() {
        synchronized(queue) {
            busySince = 0L
            while (queue.isNotEmpty()) {
                val request = queue.removeFirst()
                if (runCatching(request).getOrDefault(false)) {
                    busySince = System.currentTimeMillis()
                    return
                }
            }
        }
    }

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
        val characteristic = find(uuid) ?: return note("GATT: no characteristic ${GattNames.describe(uuid)}")
        enqueue { server.readCharacteristic(characteristic) }
    }

    /** Reads every characteristic that can be read, and every descriptor. */
    @Suppress("DEPRECATION")
    fun readAll() {
        val server = gatt ?: return note("GATT: not connected")
        var count = 0
        for (service in server.services) {
            for (characteristic in service.characteristics) {
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    enqueue { server.readCharacteristic(characteristic) }
                    count++
                }
                for (descriptor in characteristic.descriptors) {
                    enqueue { server.readDescriptor(descriptor) }
                    count++
                }
            }
        }
        enqueue { server.readRemoteRssi() }
        note("GATT: reading $count values")
    }

    @Suppress("DEPRECATION")
    fun write(uuid: UUID, bytes: ByteArray) {
        val server = gatt ?: return note("GATT: not connected")
        val characteristic = find(uuid) ?: return note("GATT: no characteristic ${GattNames.describe(uuid)}")
        val noResponse = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        enqueue {
            val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.writeCharacteristic(characteristic, bytes, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.writeType = type
                characteristic.value = bytes
                server.writeCharacteristic(characteristic)
            }
            note("GATT write ${GattNames.describe(uuid)} -> ${EventLog.hex(bytes)}: " + if (sent) "sent" else "refused")
            sent
        }
    }

    @Suppress("DEPRECATION")
    fun subscribe(uuid: UUID) {
        val server = gatt ?: return note("GATT: not connected")
        val characteristic = find(uuid) ?: return note("GATT: no characteristic ${GattNames.describe(uuid)}")
        server.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD) ?: return note("GATT: ${GattNames.describe(uuid)} has no notification switch")
        val indicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        enqueue {
            val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                descriptor.value = value
                server.writeDescriptor(descriptor)
            }
            note("GATT subscribe ${GattNames.describe(uuid)}: " + if (sent) "asked" else "refused")
            sent
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            note("GATT ${gatt.device.address}: ${if (connected) "connected" else "disconnected"}, status $status")
            state.value = "GATT ${gatt.device.address}: " + if (connected) "connected" else "disconnected"
            if (connected) {
                // The largest packets the device takes, then its services.
                enqueue { gatt.requestMtu(MAX_MTU) }
                enqueue { gatt.discoverServices() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) enqueue { gatt.readPhy(); true }
            } else {
                synchronized(queue) {
                    queue.clear()
                    busySince = 0L
                }
                found.value = emptyList()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            note("GATT: MTU $mtu, status $status")
            next()
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            note("GATT: PHY transmit $txPhy, receive $rxPhy (1: 1M, 2: 2M, 3: coded)")
            next()
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            note("GATT: signal $rssi dBm")
            next()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val list = gatt.services.flatMap { service ->
                service.characteristics.map { Characteristic(service.uuid, it.uuid, it.properties) }
            }
            found.value = list
            note("GATT: ${gatt.services.size} services, ${list.size} characteristics, status $status")
            for (service in gatt.services) {
                note("  service ${GattNames.describe(service.uuid)}")
                for (characteristic in service.characteristics) {
                    note("    ${GattNames.describe(characteristic.uuid)} ${properties(characteristic.properties)}")
                    for (descriptor in characteristic.descriptors) {
                        note("      ${GattNames.describe(descriptor.uuid)}")
                    }
                }
            }
            next()
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            received("read", characteristic.uuid, characteristic.value, status)
            next()
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            received("read ${GattNames.describe(descriptor.characteristic.uuid)} /", descriptor.uuid, descriptor.value, status)
            next()
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            received("notified", characteristic.uuid, characteristic.value, BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            note("GATT write ${GattNames.describe(characteristic.uuid)}: status $status")
            next()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            note("GATT write ${GattNames.describe(descriptor.uuid)}: status $status")
            next()
        }
    }

    private const val MAX_MTU = 517

    private fun received(what: String, uuid: UUID, value: ByteArray?, status: Int) {
        val bytes = value ?: ByteArray(0)
        val meaning = GattNames.decode(uuid, bytes)
        val text = bytes.toString(Charsets.UTF_8).filter { it >= ' ' && it != '�' }
        val shown = when {
            meaning != null -> "${EventLog.hex(bytes)}  = $meaning"
            text.isNotBlank() -> "${EventLog.hex(bytes)}  \"$text\""
            else -> EventLog.hex(bytes)
        }
        val failed = if (status != BluetoothGatt.GATT_SUCCESS) ", status $status" else ""
        note("GATT $what ${GattNames.describe(uuid)}$failed: $shown")
    }

    // --- RFCOMM -------------------------------------------------------------------------

    /** Opens a serial channel to the service [uuid] of a device. */
    suspend fun connectRfcomm(context: Context, address: String, uuid: UUID, secure: Boolean) = withContext(Dispatchers.IO) {
        closeAll()
        val device = device(context, address) ?: return@withContext note("no Bluetooth adapter")
        note("RFCOMM: connecting to $address, ${GattNames.describe(uuid)}, ${if (secure) "secure" else "insecure"}")
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
        synchronized(queue) {
            queue.clear()
            busySince = 0L
        }
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

    /** "2A19 Battery Level" for a known UUID, "2A19" or the whole UUID for any other. */
    fun name(uuid: UUID): String = GattNames.describe(uuid)

    private fun properties(value: Int): String = listOfNotNull(
        "read".takeIf { value and BluetoothGattCharacteristic.PROPERTY_READ != 0 },
        "write".takeIf { value and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 },
        "write-no-response".takeIf { value and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 },
        "notify".takeIf { value and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 },
        "indicate".takeIf { value and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 },
        "signed".takeIf { value and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0 },
    ).joinToString(", ", "[", "]")

    /** Turns "2A19" or a whole UUID into a UUID, or null. */
    fun uuid(text: String): UUID? {
        val clean = text.trim().substringBefore(' ')
        return try {
            if (clean.length == 4) UUID.fromString(short(clean.toInt(16))) else UUID.fromString(clean)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
