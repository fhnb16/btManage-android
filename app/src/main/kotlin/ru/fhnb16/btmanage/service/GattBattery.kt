package ru.fhnb16.btmanage.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import ru.fhnb16.btmanage.data.EventLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the level of Bluetooth LE devices Android holds none for: mice, keyboards, remotes
 * through the standard Battery Service; watches and bands through it too where they offer
 * it, and Xiaomi Mi Band and Amazfit (Huami) bands through their own battery
 * characteristic, which they let any client read, as Gadgetbridge reads it. One read per
 * device every few minutes; the link the device already has, often held by the maker's own
 * application, stays as it is.
 *
 * Not reached this way: Wear OS watches report their battery to the phone only through
 * Google's Wear OS data layer, and Samsung's through Galaxy Wearable; both need an
 * application of our own on the watch. Newer Xiaomi bands encrypt their protocol.
 *
 * Written without a run on a device.
 */
@SuppressLint("MissingPermission")
class GattBattery(
    private val context: Context,
    private val onLevel: (address: String, percent: Int, charging: Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val triedAt = ConcurrentHashMap<String, Long>()
    private val open = ConcurrentHashMap<String, BluetoothGatt>()

    /** Reads the level of each of these devices that was not read recently. */
    fun poll(devices: List<BluetoothDevice>) {
        val now = System.currentTimeMillis()
        for (device in devices) {
            val address = device.address
            if (open.containsKey(address) || now - (triedAt[address] ?: 0L) < EVERY_MS) continue
            triedAt[address] = now
            handler.post { read(device) }
        }
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        for (gatt in open.values.toList()) close(gatt)
    }

    private fun read(device: BluetoothDevice) {
        val gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Throwable) {
            null
        }
        if (gatt == null) return
        open[device.address] = gatt
        // A device that does not answer must not keep the client open.
        handler.postDelayed({ close(gatt) }, TIMEOUT_MS)
    }

    private fun close(gatt: BluetoothGatt) {
        if (!open.remove(gatt.device.address, gatt)) return
        try {
            gatt.disconnect()
            gatt.close()
        } catch (_: Throwable) {
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val connected = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
            EventLog.note("gatt", "${gatt.device.address}: state $newState, status $status")
            if (!connected || !gatt.discoverServices()) close(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // The standard one first; a band of Huami's where that is absent.
            val level = gatt.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
                ?: gatt.getService(HUAMI_SERVICE)?.getCharacteristic(HUAMI_BATTERY)
            val found = when (level?.uuid) {
                BATTERY_LEVEL -> "Battery Service"
                HUAMI_BATTERY -> "Huami battery"
                else -> "absent"
            }
            EventLog.note("gatt", "${gatt.device.address}: ${gatt.services.size} services, battery $found")
            if (status != BluetoothGatt.GATT_SUCCESS || level == null || !gatt.readCharacteristic(level)) close(gatt)
        }

        // Android 13 added a variant that carries the value; its default calls this one.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val value = characteristic.value
            if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                val reading = when (characteristic.uuid) {
                    BATTERY_LEVEL -> value.getOrNull(0)?.let { (it.toInt() and 0xFF) to false }
                    // Huami: the level in the second byte, 1 in the third while charging.
                    HUAMI_BATTERY -> value.getOrNull(1)?.let { (it.toInt() and 0xFF) to (value.getOrNull(2)?.toInt() == 1) }
                    else -> null
                }
                if (reading != null) {
                    val (percent, charging) = reading
                    EventLog.note("gatt", "${gatt.device.address}: battery $percent %${if (charging) ", charging" else ""} (${EventLog.hex(value)})")
                    if (percent <= 100) onLevel(gatt.device.address, percent, charging)
                }
            }
            close(gatt)
        }
    }

    private companion object {
        const val EVERY_MS = 10 * 60 * 1000L
        const val TIMEOUT_MS = 20_000L
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        /** Mi Band 2 to 7 and Amazfit bands and watches. */
        val HUAMI_SERVICE: UUID = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
        val HUAMI_BATTERY: UUID = UUID.fromString("00000006-0000-3512-2118-0009af100700")
    }
}
