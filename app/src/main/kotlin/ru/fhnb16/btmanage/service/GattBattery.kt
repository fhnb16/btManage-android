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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the Battery Service of Bluetooth LE devices Android holds no level for: mice,
 * keyboards, remotes. One read per device every few minutes; the link the device already
 * has stays as it is.
 *
 * Written without a run on a device.
 */
@SuppressLint("MissingPermission")
class GattBattery(private val context: Context, private val onLevel: (address: String, percent: Int) -> Unit) {
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
            if (!connected || !gatt.discoverServices()) close(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val level = gatt.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
            if (status != BluetoothGatt.GATT_SUCCESS || level == null || !gatt.readCharacteristic(level)) close(gatt)
        }

        // Android 13 added a variant that carries the value; its default calls this one.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val value = characteristic.value
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL && value != null && value.isNotEmpty()) {
                val percent = value[0].toInt() and 0xFF
                if (percent <= 100) onLevel(gatt.device.address, percent)
            }
            close(gatt)
        }
    }

    private companion object {
        const val EVERY_MS = 10 * 60 * 1000L
        const val TIMEOUT_MS = 20_000L
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }
}
