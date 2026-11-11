package ru.fhnb16.btmanage.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.fhnb16.btmanage.MainActivity
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.Devices
import ru.fhnb16.btmanage.data.Settings
import ru.fhnb16.btmanage.ui.DeviceText
import ru.fhnb16.btmanage.widget.BatteryWidget

/**
 * Watches the Bluetooth devices while the application is in the background: listens for
 * the advertisements earbuds send their levels in, follows the paired devices and their
 * batteries, keeps the widgets current and shows notifications.
 *
 * It runs as a foreground service of the "connected device" type, with a quiet ongoing
 * notification that carries the levels.
 */
class DeviceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adapter: BluetoothAdapter? by lazy { getSystemService(BluetoothManager::class.java)?.adapter }

    /** Levels and connections Android reports through broadcasts, by device address. */
    private val batteries = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val connected: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** What was last announced per device: whether it was active, and which batteries were low. */
    private val seen = mutableMapOf<String, Pair<Boolean, Int>>()
    private var primed = false
    private var scanning = false
    private var widgetSignature = ""
    private var statusText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Settings.load(this)
        Devices.open(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasPermissions(this) || !Devices.available) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = statusNotification(getString(R.string.status_watching))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(STATUS_ID, notification)
        }

        if (!running) {
            running = true
            registerReceivers()
            scope.launch { watch() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopScan()
        runCatching { unregisterReceiver(receiver) }
        Devices.save()
        scope.cancel()
        super.onDestroy()
    }

    /** The loop of the service: scan, read the paired devices, tell the user, save. */
    private suspend fun watch() {
        var tick = 0
        while (scope.isActive) {
            if (adapter?.isEnabled == true) startScan() else scanning = false
            if (tick % SYSTEM_EVERY == 0) readSystemDevices()
            Devices.refresh()
            onDevices(Devices.list.value)
            if (tick % SAVE_EVERY == SAVE_EVERY - 1) Devices.save()
            tick++
            delay(TICK_MS)
        }
    }

    // --- Advertisements ---------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val address = result.device.address.replace(":", "").toLongOrNull(16) ?: return
            record.manufacturerSpecificData?.let { data ->
                for (index in 0 until data.size()) {
                    Devices.advertisement(address, result.rssi, Devices.ADV_MANUFACTURER, data.keyAt(index), data.valueAt(index))
                }
            }
            record.serviceData?.forEach { (uuid, data) ->
                shortUuid(uuid)?.let { Devices.advertisement(address, result.rssi, Devices.ADV_SERVICE_DATA, it, data) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        // With filters Android keeps delivering results while the screen is off.
        val filters = listOf(
            ScanFilter.Builder().setManufacturerData(APPLE, byteArrayOf()).build(),
            ScanFilter.Builder().setServiceData(FAST_PAIR, byteArrayOf()).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        scanning = runCatching { scanner.startScan(filters, settings, scanCallback) }.isSuccess
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
    }

    // --- Paired devices -----------------------------------------------------------------

    /** Describes the paired devices to the core, in the shape of its `SystemDevice`. */
    @SuppressLint("MissingPermission")
    private fun readSystemDevices() {
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull() ?: return
        val devices = JSONArray()
        for (device in bonded) {
            val address = device.address
            val isConnected = isConnected(device) ?: (address in connected)
            val services = JSONArray()
            device.uuids?.mapNotNull { shortUuid(it) }?.distinct()?.forEach(services::put)
            val battery = batteryLevel(device) ?: batteries[address]

            devices.put(
                JSONObject().apply {
                    put("id", address)
                    put("name", runCatching { device.alias }.getOrNull() ?: device.name ?: address)
                    put("address", address.replace(":", "").toLong(16))
                    put(
                        "transport",
                        when (device.type) {
                            BluetoothDevice.DEVICE_TYPE_LE -> "le"
                            BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                            else -> "classic"
                        },
                    )
                    put("connected", isConnected)
                    // The device class holds the major and minor class in the bits the core reads.
                    device.bluetoothClass?.let { put("class_of_device", it.deviceClass) }
                    put("services", services)
                    if (isConnected && battery != null && battery in 0..100) put("battery", battery)
                },
            )
        }
        Devices.setSystemDevices(devices.toString())
    }

    /**
     * Whether a paired device is connected. Android keeps this call out of the public
     * interface; it has been there, unchanged, for many versions. Null when it is not.
     */
    private fun isConnected(device: BluetoothDevice): Boolean? = runCatching {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as? Boolean
    }.getOrNull()

    /** The level Android itself holds for a device (headsets report it), if any. */
    private fun batteryLevel(device: BluetoothDevice): Int? = runCatching {
        (BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as? Int)?.takeIf { it in 0..100 }
    }.getOrNull()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val address = device?.address
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> address?.let(connected::add)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> address?.let {
                    connected.remove(it)
                    batteries.remove(it)
                }
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    if (address != null && level in 0..100) batteries[address] = level
                }
            }
            // Bonding, the adapter switching on or off, and all of the above change the list.
            scope.launch {
                readSystemDevices()
                onDevices(Devices.list.value)
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    // --- Telling the user ---------------------------------------------------------------

    private suspend fun onDevices(devices: List<Device>) {
        val settings = Settings.current
        val threshold = settings.lowThreshold

        // The ongoing notification carries the levels of the active devices.
        val status = devices.filter { it.isActive && it.cells.isNotEmpty() }.take(3)
            .joinToString("\n") { "${DeviceText.name(this, it)}: ${DeviceText.levels(this, it)}" }
            .ifEmpty { getString(R.string.status_watching) }
        if (status != statusText) {
            statusText = status
            notifications().notify(STATUS_ID, statusNotification(status))
        }

        for (device in devices) {
            val lowMask = device.cells.filter { it.isFresh && it.isLow(threshold) }
                .fold(0) { mask, cell -> mask or (1 shl cell.role.ordinal) }
            val (wasActive, wasLow) = seen[device.id] ?: (false to 0)
            seen[device.id] = device.isActive to lowMask

            // Only the user's own devices are news, and nothing is news on the first pass.
            if (!primed || !device.isActive || !device.paired || device.id in settings.muted) continue
            val audio = device.kind in AUDIO_KINDS
            if (!wasActive && audio && device.cells.isNotEmpty()) {
                if (settings.notifyArrival) alert(device, DeviceText.levels(this, device))
            } else if (lowMask and wasLow.inv() != 0 && settings.notifyLow) {
                val low = device.cells.filter { it.isFresh && it.isLow(threshold) }
                    .joinToString(", ") { DeviceText.cellText(this, device, it) }
                alert(device, getString(R.string.toast_low, low))
            }
        }
        seen.keys.retainAll(devices.map { it.id }.toSet())
        primed = true

        // Widgets are redrawn when what they show changes.
        val signature = devices.joinToString("|") { device ->
            "${device.id}:${device.isActive}:" + device.cells.joinToString(",") { "${it.percent}${it.charging}${it.isFresh}" }
        } + settings.widget
        if (signature != widgetSignature) {
            widgetSignature = signature
            runCatching { BatteryWidget().updateAll(this) }
        }
    }

    private fun alert(device: Device, text: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        val notification = Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(DeviceText.name(this, device))
            .setContentText(text)
            .setContentIntent(openApp(device.id))
            .setAutoCancel(true)
            .build()
        notifications().notify(device.id.hashCode(), notification)
    }

    private fun statusNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(null))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun openApp(deviceId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_DEVICE, deviceId)
        return PendingIntent.getActivity(
            this, deviceId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notifications() = getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        notifications().createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    companion object {
        private const val TICK_MS = 5_000L
        private const val SYSTEM_EVERY = 6
        private const val SAVE_EVERY = 60
        private const val STATUS_ID = 1
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALERTS = "alerts"
        private const val APPLE = 0x004C
        private val FAST_PAIR: ParcelUuid = ParcelUuid.fromString("0000fe2c-0000-1000-8000-00805f9b34fb")
        private val AUDIO_KINDS = setOf("earbuds", "headphones", "headset", "speaker")

        /** Broadcast with the battery of a connected device; not in the public interface, but stable. */
        private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        @Volatile
        var running = false
            private set

        /** The permissions scanning and reading the paired devices need on this version of Android. */
        fun requiredPermissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.toTypedArray()

        fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        fun start(context: Context) {
            if (hasPermissions(context) && Devices.available) {
                ContextCompat.startForegroundService(context, Intent(context, DeviceService::class.java))
            }
        }

        /** The 16-bit form of a UUID on the Bluetooth base, or null for any other UUID. */
        private fun shortUuid(uuid: ParcelUuid): Int? {
            val value = uuid.uuid
            val onBase = value.leastSignificantBits == -0x7fffff7fa064cb05L &&
                value.mostSignificantBits and 0xFFFF0000FFFFFFFFuL.toLong() == 0x0000000000001000L
            return if (onBase) ((value.mostSignificantBits ushr 32) and 0xFFFF).toInt() else null
        }
    }
}

/** Starts the service when the phone starts, if the user wants that. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Settings.load(context)
        if (Settings.current.startOnBoot) DeviceService.start(context)
    }
}
