package ru.fhnb16.btmanage.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.fhnb16.btmanage.service.DeviceService

/** A device a search turned up, and how far pairing with it has got. */
data class Found(
    val address: String,
    val name: String,
    /** Signal strength in dBm, when Android told. */
    val rssi: Int?,
    val pairing: Boolean,
    val paired: Boolean,
)

/**
 * The search for devices to pair with, and the pairing. Android's own discovery does the
 * searching: it finds Bluetooth Classic devices that are discoverable and Bluetooth LE
 * devices that advertise. Pairing is Android's too: it shows its own dialog for the code
 * or the confirmation.
 *
 * Written without a run on a device.
 */
object Discovery {
    enum class Phase { IDLE, SEARCHING, DONE, UNAVAILABLE }

    private val foundFlow = MutableStateFlow<List<Found>>(emptyList())

    /** Devices with a name, nearest first. Devices already paired before the search are left out. */
    val found: StateFlow<List<Found>> = foundFlow

    private val phaseFlow = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = phaseFlow

    private val seen = LinkedHashMap<String, Found>()
    private var app: Context? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> phaseFlow.value = Phase.SEARCHING
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (phaseFlow.value == Phase.SEARCHING) phaseFlow.value = Phase.DONE
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> if (device != null) {
                    val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                        ?: runCatching { device.name }.getOrNull()
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val state = runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
                    note(device.address, name, if (rssi == Short.MIN_VALUE) null else rssi.toInt(), state)
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (device != null) {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    note(device.address, null, null, state)
                }
            }
        }
    }

    /** Starts a search from nothing. Call [stop] when the list is no longer on screen. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context) {
        val application = context.applicationContext
        app = application
        val adapter = application.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled || !DeviceService.hasPermissions(application)) {
            phaseFlow.value = Phase.UNAVAILABLE
            return
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(application, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        seen.clear()
        foundFlow.value = emptyList()
        val started = try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        } catch (_: Throwable) {
            false
        }
        phaseFlow.value = if (started) Phase.SEARCHING else Phase.UNAVAILABLE
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop() {
        val application = app ?: return
        try {
            application.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery()
        } catch (_: Throwable) {
        }
        if (registered) {
            runCatching { application.unregisterReceiver(receiver) }
            registered = false
        }
        phaseFlow.value = Phase.IDLE
    }

    /**
     * Asks Android to pair with a device from the list. Android takes it from there, with
     * its own dialog where the device wants a code. False when Android refused outright.
     */
    @SuppressLint("MissingPermission")
    fun pair(address: String): Boolean {
        val adapter = app?.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return try {
            // A search in progress gets in the way of the pairing.
            adapter.cancelDiscovery()
            val asked = adapter.getRemoteDevice(address).createBond()
            if (asked) note(address, null, null, BluetoothDevice.BOND_BONDING)
            asked
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Before Android 12 a search finds nothing while Location is switched off; from
     * Android 12 the application declares that it does not use the results for location.
     */
    fun blockedByLocation(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return !LocationManagerCompat.isLocationEnabled(manager)
    }

    @Synchronized
    private fun note(address: String, name: String?, rssi: Int?, bondState: Int) {
        val known = seen[address]
        val paired = bondState == BluetoothDevice.BOND_BONDED
        // What was paired before this search belongs to the device list, not here.
        if (known == null && (paired || name.isNullOrBlank())) return
        seen[address] = Found(
            address = address,
            name = name?.takeIf { it.isNotBlank() } ?: known?.name ?: return,
            rssi = rssi ?: known?.rssi,
            pairing = bondState == BluetoothDevice.BOND_BONDING,
            paired = paired,
        )
        foundFlow.value = seen.values.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
    }
}
