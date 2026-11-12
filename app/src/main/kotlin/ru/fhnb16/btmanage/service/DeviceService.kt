package ru.fhnb16.btmanage.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import ru.fhnb16.btmanage.MainActivity
import ru.fhnb16.btmanage.R
import ru.fhnb16.btmanage.data.Device
import ru.fhnb16.btmanage.data.DeviceFacts
import ru.fhnb16.btmanage.data.Devices
import ru.fhnb16.btmanage.data.Diagnostics
import ru.fhnb16.btmanage.data.Settings
import ru.fhnb16.btmanage.data.Updates
import ru.fhnb16.btmanage.ui.DeviceText
import ru.fhnb16.btmanage.widget.BatteryWidget
import java.util.concurrent.atomic.AtomicInteger

/**
 * Watches the Bluetooth devices while the application is in the background: listens for
 * the advertisements earbuds send their levels in, follows the paired devices and their
 * batteries, keeps the widgets current and shows notifications.
 *
 * A level can come from several places, and each is listened to: Android's own record for
 * a paired device, the broadcast Android sends when that record changes, the events a
 * headset sends over its hands-free link, the Battery Service of a Bluetooth LE device,
 * and the advertisements.
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

    /** Product identifiers Apple accessories gave over their hands-free link, by address. */
    private lateinit var appleProducts: SharedPreferences
    private lateinit var gatt: GattBattery

    /** One pass over the devices at a time: the loop and the broadcasts both ask for one. */
    private val gate = Mutex()

    /** What was last announced per device: whether it was active, and which batteries were low. */
    private val seen = mutableMapOf<String, Pair<Boolean, Int>>()
    private var primed = false
    private var widgetSignature = ""
    private var statusText = ""

    @Volatile
    private var scanning = false

    @Volatile
    private var anyConnected = false

    /** Whether the running scan takes everything, as it may while the application is on screen. */
    private var scanOpen = false
    private var scanStartedAt = 0L

    /** When updates were last looked for; set so that the first look comes shortly after start. */
    private var updateCheckedAt = System.currentTimeMillis() - UPDATE_EVERY_MS + UPDATE_FIRST_MS
    private var announcedVersion: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Settings.load(this)
        Devices.open(this)
        appleProducts = getSharedPreferences("apple-products", Context.MODE_PRIVATE)
        gatt = GattBattery(this) { address, percent ->
            Diagnostics.lastLevel = "GATT $percent %"
            Devices.battery(address, Devices.SOURCE_GATT, percent, false)
        }
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
            Links.open(this)
            registerReceivers()
            scope.launch { watch() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopScan()
        gatt.close()
        runCatching { unregisterReceiver(receiver) }
        Devices.save()
        scope.cancel()
        super.onDestroy()
    }

    /** The loop of the service: scan, read the paired devices, tell the user, save. */
    private suspend fun watch() {
        var tick = 0
        while (scope.isActive) {
            keepScanning()
            gate.withLock {
                if (tick % SYSTEM_EVERY == 0) readSystemDevices()
                Devices.refresh()
                onDevices(Devices.list.value)
            }
            // What Android itself knows about the connected devices, where Shizuku or root
            // lets the application read it.
            if (tick % FACTS_EVERY == 1 && anyConnected) {
                scope.launch { runCatching { DeviceFacts.refresh(this@DeviceService) } }
            }
            if (tick % SAVE_EVERY == SAVE_EVERY - 1) Devices.save()
            checkForUpdate()
            tick++
            delay(TICK_MS)
        }
    }

    /**
     * Once a day: looks for a newer release and, if the user wants updates installed on
     * their own, downloads and installs it. That happens only where a download costs
     * nothing and Android already lets the application install packages; otherwise the
     * user is told once that there is a newer version.
     */
    private fun checkForUpdate() {
        val now = System.currentTimeMillis()
        val settings = Settings.current
        if (!settings.checkUpdates || now - updateCheckedAt < UPDATE_EVERY_MS) return
        updateCheckedAt = now

        scope.launch {
            val release = Updates.check() ?: return@launch
            val service = this@DeviceService
            val onItsOwn = settings.autoUpdate && release.apkUrl != null &&
                Updates.canInstall(service) && !Updates.isMetered(service)
            if (onItsOwn) {
                Updates.install(service)
            } else if (release.version != announcedVersion) {
                announcedVersion = release.version
                notice(getString(R.string.update_available, release.version))
            }
        }
    }

    // --- Advertisements ---------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onAdvertisement(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) onAdvertisement(result)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Diagnostics.scan = "failed, code $errorCode"
        }
    }

    private fun onAdvertisement(result: ScanResult) {
        val record = result.scanRecord ?: return
        Diagnostics.adverts.incrementAndGet()
        Diagnostics.lastAdvertAt = System.currentTimeMillis()
        val address = result.device.address.replace(":", "").toLongOrNull(16) ?: return

        // Of everything Apple devices send, only the proximity pairing message carries
        // levels. It is looked for in the packet as it came: Android's own reading keeps one
        // block of data per company, and the last one wins.
        val packet = record.bytes
        var proximity = if (packet == null) {
            null
        } else {
            appleProximity(packet) { type ->
                Diagnostics.appleTypes.getOrPut(type) { AtomicInteger() }.incrementAndGet()
            }
        }
        if (proximity == null) {
            proximity = record.getManufacturerSpecificData(APPLE)?.takeIf { it.isNotEmpty() && it[0] == PROXIMITY_PAIRING }
        }
        if (proximity != null) {
            Diagnostics.appleAdverts.incrementAndGet()
            Diagnostics.lastProximity = proximity.take(10).joinToString(" ") { "%02X".format(it) } +
                ", ${proximity.size} bytes, ${result.rssi} dBm"
            Devices.advertisement(address, result.rssi, Devices.ADV_MANUFACTURER, APPLE, proximity)
        }
        val fastPair = record.serviceData?.get(FAST_PAIR)
        if (fastPair != null) {
            Diagnostics.fastPairAdverts.incrementAndGet()
            Devices.advertisement(address, result.rssi, Devices.ADV_SERVICE_DATA, FAST_PAIR_UUID, fastPair)
        }
    }

    /**
     * Keeps a scan running. Android quietly stops delivering to a scan that has run for
     * half an hour, so it is started anew before that. While the application is on screen
     * the scan takes everything and listens all the time; in the background it must carry
     * filters, or Android pauses it when the screen goes off, and it listens sparingly.
     */
    private fun keepScanning() {
        if (adapter?.isEnabled != true) {
            scanning = false
            Diagnostics.scan = "Bluetooth is off"
            return
        }
        val open = Updates.foreground
        val age = System.currentTimeMillis() - scanStartedAt
        // Not more often than twice a minute: Android refuses applications that restart scans rapidly.
        if (scanning && age > SCAN_SETTLE_MS && (open != scanOpen || age > SCAN_RESTART_MS)) stopScan()
        if (!scanning && age > SCAN_SETTLE_MS) startScan(open)
    }

    @SuppressLint("MissingPermission")
    private fun startScan(open: Boolean) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filters = if (open) {
            null
        } else {
            listOf(
                ScanFilter.Builder()
                    .setManufacturerData(APPLE, byteArrayOf(PROXIMITY_PAIRING), byteArrayOf(0xFF.toByte()))
                    .build(),
                ScanFilter.Builder().setServiceData(FAST_PAIR, byteArrayOf()).build(),
            )
        }
        val mode = if (open) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER
        val settings = ScanSettings.Builder().setScanMode(mode).build()
        scanStartedAt = System.currentTimeMillis()
        scanOpen = open
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            Diagnostics.scanStarts.incrementAndGet()
            Diagnostics.scan = if (open) "running, unfiltered, low latency" else "running, filtered, low power"
        } catch (error: Throwable) {
            scanning = false
            Diagnostics.scan = "could not start: ${error.javaClass.simpleName}"
        }
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
        val linked = Links.connectedAddresses()
        val devices = JSONArray()
        val unread = ArrayList<BluetoothDevice>()
        val described = ArrayList<String>()
        var connectedCount = 0

        for (device in bonded) {
            val address = device.address
            val isConnected = isConnected(device) == true || address in linked || address in connected
            if (isConnected) connectedCount++
            val name = runCatching { device.alias }.getOrNull() ?: device.name ?: address
            val uuids = runCatching { device.uuids }.getOrNull()
            val services = uuids?.mapNotNull { shortUuid(it) }?.distinct().orEmpty()
            val battery = batteryLevel(device) ?: batteries[address]

            // How the core is to match this pairing with what Apple accessories broadcast:
            // by its name; by the product it named over the hands-free link; or, under a
            // name of the user's own, as an Apple accessory of a model not known, which
            // shows in the service only AirPods and Beats offer.
            val product = appleProducts.getInt(address, -1)
            val named = name.contains("airpods", ignoreCase = true) || name.contains("beats", ignoreCase = true)
            val accessory = uuids?.any { it.uuid == APPLE_ACCESSORY } == true
            val apple = when {
                named -> "name"
                product in 0..0xFFFF -> "product %04X".format(product)
                accessory -> "service"
                else -> "no"
            }

            devices.put(
                JSONObject().apply {
                    put("id", address)
                    put("name", name)
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
                    put("services", JSONArray(services))
                    if (isConnected && battery != null && battery in 0..100) put("battery", battery)

                    if (apple != "name" && apple != "no") {
                        put("vendor_source", 1)
                        put("vendor_id", APPLE)
                        if (apple != "service") put("product_id", product)
                    }
                },
            )
            val transport = when (device.type) {
                BluetoothDevice.DEVICE_TYPE_LE -> "LE"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                else -> "classic"
            }
            described += "  $name: $transport, ${if (isConnected) "connected" else "not connected"}, " +
                "battery ${battery ?: "none"}, Apple: $apple"

            // AirPods and Beats keep a channel of their own for levels and settings.
            if (isConnected && accessory) Aap.keep(this, address)

            // Bluetooth LE devices Android has no level for are asked directly.
            val overLe = device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                (device.type == BluetoothDevice.DEVICE_TYPE_DUAL && BATTERY_SERVICE_UUID in services)
            if (isConnected && battery == null && overLe) unread.add(device)
        }

        Diagnostics.paired = bonded.size
        Diagnostics.connected = connectedCount
        Diagnostics.pairedDevices = described.joinToString("\n")
        anyConnected = connectedCount > 0
        Devices.setSystemDevices(devices.toString())
        if (unread.isNotEmpty()) gatt.poll(unread)
    }

    /**
     * Whether a paired device is connected. Android keeps this call out of the public
     * interface; it has been there, unchanged, for many versions. Null when it is not.
     */
    private fun isConnected(device: BluetoothDevice): Boolean? {
        val answer = try {
            BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as? Boolean
        } catch (_: Throwable) {
            null
        }
        Diagnostics.connectedCall = if (answer == null) "refused" else "answers"
        return answer
    }

    /** The level Android itself holds for a device (headsets report it), if any. */
    private fun batteryLevel(device: BluetoothDevice): Int? {
        val answer = try {
            BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as? Int
        } catch (_: Throwable) {
            null
        }
        Diagnostics.batteryCall = if (answer == null) "refused" else "answers"
        return answer?.takeIf { it in 0..100 }
    }

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
                    Aap.forget(it)
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        // The scan died with the adapter, and so did every link.
                        scanning = false
                        connected.clear()
                        batteries.clear()
                    }
                }
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val percent = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    if (address != null) noteLevel(address, percent, "broadcast")
                }
                ACTION_HF_INDICATOR -> {
                    // Indicator 2 of the hands-free profile is the battery, in percent.
                    val indicator = intent.getIntExtra(EXTRA_HF_INDICATOR_ID, -1)
                    val value = intent.getIntExtra(EXTRA_HF_INDICATOR_VALUE, -1)
                    if (address != null && indicator == 2) noteLevel(address, value, "hands-free indicator")
                }
                BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                    if (address != null) runCatching { onHeadsetEvent(address, intent) }
                }
            }
            // Bonding, the adapter switching on or off, and all of the above change the list.
            scope.launch {
                gate.withLock {
                    readSystemDevices()
                    onDevices(Devices.list.value)
                }
            }
        }
    }

    private fun noteLevel(address: String, percent: Int, from: String) {
        if (percent !in 0..100) return
        batteries[address] = percent
        Diagnostics.lastLevel = "$from $percent %"
    }

    /**
     * What a headset says about itself over the hands-free link, in the commands Apple and
     * Plantronics defined and most headsets speak.
     */
    private fun onHeadsetEvent(address: String, intent: Intent) {
        val command = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD) ?: return

        @Suppress("DEPRECATION")
        val arguments = intent.extras?.get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS) as? Array<*> ?: return
        when (command) {
            // "vendor-product-version", in hexadecimal.
            "+XAPL" -> {
                val product = (arguments.getOrNull(0) as? String)?.split('-')?.getOrNull(1)?.toIntOrNull(16)
                if (product != null) appleProducts.edit().putInt(address, product).apply()
            }
            // A count, then pairs of key and value. Key 1 is the battery, in tenths from 0 to 9.
            "+IPHONEACCEV" -> {
                var index = 1
                while (index + 1 < arguments.size) {
                    val value = arguments[index + 1] as? Int
                    if (arguments[index] == 1 && value != null) noteLevel(address, (value + 1) * 10, "+IPHONEACCEV")
                    index += 2
                }
            }
            // "BATTERY", the level, the number of levels.
            "+XEVENT" -> {
                val value = arguments.getOrNull(1) as? Int
                val steps = arguments.getOrNull(2) as? Int
                if (arguments.getOrNull(0) == "BATTERY" && value != null && steps != null && steps > 1) {
                    noteLevel(address, value * 100 / (steps - 1), "+XEVENT")
                }
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(ACTION_HF_INDICATOR)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        // Headset events carry the company that defined the command as a category, and
        // only a filter naming that category receives them.
        val headset = IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
        for (company in intArrayOf(APPLE, PLANTRONICS)) {
            headset.addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + "." + company)
        }
        ContextCompat.registerReceiver(this, receiver, headset, ContextCompat.RECEIVER_EXPORTED)
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
        } + settings.widget + settings.design
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

    /** A notification that is not about a device; it opens the application. */
    private fun notice(text: String) {
        val notification = Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp(null))
            .setAutoCancel(true)
            .build()
        runCatching { notifications().notify(NOTICE_ID, notification) }
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
        private const val FACTS_EVERY = 12
        private const val STATUS_ID = 1
        private const val NOTICE_ID = 3
        private const val UPDATE_EVERY_MS = 24 * 60 * 60 * 1000L
        private const val UPDATE_FIRST_MS = 10 * 60 * 1000L
        private const val SCAN_SETTLE_MS = 30_000L
        private const val SCAN_RESTART_MS = 20 * 60 * 1000L
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALERTS = "alerts"
        private const val APPLE = 0x004C
        private const val PLANTRONICS = 0x0055
        private const val PROXIMITY_PAIRING: Byte = 0x07
        private const val FAST_PAIR_UUID = 0xFE2C
        private const val BATTERY_SERVICE_UUID = 0x180F
        private val FAST_PAIR: ParcelUuid = ParcelUuid.fromString("0000fe2c-0000-1000-8000-00805f9b34fb")

        /** The service of Apple's own accessory protocol, which AirPods and Beats list. */
        private val APPLE_ACCESSORY: java.util.UUID = java.util.UUID.fromString(Aap.ACCESSORY_UUID)
        private val AUDIO_KINDS = setOf("earbuds", "headphones", "headset", "speaker")

        // Broadcasts Android sends about a connected device. They are not in the public
        // interface, but have kept these names for many versions.
        private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        private const val ACTION_HF_INDICATOR = "android.bluetooth.headset.action.HF_INDICATORS_VALUE_CHANGED"
        private const val EXTRA_HF_INDICATOR_ID = "android.bluetooth.headset.extra.HF_INDICATORS_IND_ID"
        private const val EXTRA_HF_INDICATOR_VALUE = "android.bluetooth.headset.extra.HF_INDICATORS_IND_VALUE"

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

/**
 * Finds Apple's proximity pairing message in an advertising packet as it came off the air:
 * a run of blocks of length, type and data, of which the manufacturer blocks of Apple
 * (type FF, company 4C 00) hold messages of type, length and payload. Returns the message
 * with its type and length bytes, as the core reads it. [seen] is told the type of every
 * Apple message on the way.
 */
internal fun appleProximity(packet: ByteArray, seen: (Int) -> Unit = {}): ByteArray? {
    var found: ByteArray? = null
    var block = 0
    while (block < packet.size) {
        val length = packet[block].toInt() and 0xFF
        val end = block + 1 + length
        if (length == 0 || end > packet.size) break
        val apple = length >= 3 &&
            packet[block + 1] == 0xFF.toByte() &&
            packet[block + 2] == 0x4C.toByte() &&
            packet[block + 3] == 0x00.toByte()
        if (apple) {
            var message = block + 4
            while (message + 1 < end) {
                val type = packet[message].toInt() and 0xFF
                val next = message + 2 + (packet[message + 1].toInt() and 0xFF)
                seen(type)
                // A message the packet cuts short is handed on as it is; the core judges it.
                if (type == 0x07 && found == null) found = packet.copyOfRange(message, minOf(next, end))
                message = next
            }
        }
        block = end
    }
    return found
}

/**
 * Starts the service when the phone starts, if the user wants that, and again after the
 * application has been updated: an update stops everything that was running.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Settings.load(context)
        if (Settings.current.startOnBoot) DeviceService.start(context)
    }
}
