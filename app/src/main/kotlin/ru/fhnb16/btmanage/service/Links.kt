package ru.fhnb16.btmanage.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import ru.fhnb16.btmanage.data.Diagnostics
import java.util.concurrent.ConcurrentHashMap

/**
 * The profile services of Android's Bluetooth stack: audio, hands-free, input devices,
 * hearing aids, LE Audio. They are the public way to learn which devices are connected,
 * and the place where the codec of an audio link can be read.
 *
 * Written without a run on a device.
 */
object Links {
    private const val HEADSET = 1
    private const val A2DP = 2
    private const val HID_HOST = 4
    private const val HEARING_AID = 21
    private const val LE_AUDIO = 22
    private val PROFILES = intArrayOf(HEADSET, A2DP, HID_HOST, HEARING_AID, LE_AUDIO)

    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()
    private var manager: BluetoothManager? = null
    private var opened = false

    /** Asks Android for the profile services. Needs the Bluetooth permissions granted. */
    @Synchronized
    fun open(context: Context) {
        if (opened) return
        val app = context.applicationContext
        val bluetooth = app.getSystemService(BluetoothManager::class.java) ?: return
        val adapter = bluetooth.adapter ?: return
        opened = true
        manager = bluetooth

        // Android 9 to 12 keep the battery level and the codec of a paired device away from
        // applications that target a current version. From Android 13 these calls are
        // reachable, so nothing is unlocked there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")
            } catch (_: Throwable) {
            }
        }

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxies[profile] = proxy
                Diagnostics.profiles = proxies.keys.sorted().joinToString(", ")
            }

            override fun onServiceDisconnected(profile: Int) {
                proxies.remove(profile)
                Diagnostics.profiles = proxies.keys.sorted().joinToString(", ")
            }
        }
        for (profile in PROFILES) {
            try {
                adapter.getProfileProxy(app, listener, profile)
            } catch (_: Throwable) {
            }
        }
    }

    /** Addresses of the devices connected on any profile, or over GATT. */
    @SuppressLint("MissingPermission")
    fun connectedAddresses(): Set<String> {
        val found = HashSet<String>()
        for (proxy in proxies.values) {
            try {
                for (device in proxy.connectedDevices) found.add(device.address)
            } catch (_: Throwable) {
            }
        }
        try {
            manager?.getConnectedDevices(BluetoothProfile.GATT)?.forEach { found.add(it.address) }
        } catch (_: Throwable) {
        }
        return found
    }

    /**
     * Connects or disconnects a device on each profile service, the way applications could
     * before Android 12. True when at least one service took the request.
     */
    fun link(device: BluetoothDevice, connect: Boolean): Boolean {
        val name = if (connect) "connect" else "disconnect"
        var taken = false
        for (proxy in proxies.values) {
            try {
                val result = proxy.javaClass.getMethod(name, BluetoothDevice::class.java).invoke(proxy, device)
                if (result == true) taken = true
            } catch (_: Throwable) {
            }
        }
        return taken
    }

    /**
     * The codec of the audio link to a device, as "AAC, 44.1 kHz". Null when the device has
     * no audio link or Android does not tell: from Android 13 only privileged applications
     * are told.
     */
    fun codec(device: BluetoothDevice): String? {
        val a2dp = proxies[A2DP] ?: return null
        try {
            val status = a2dp.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(a2dp, device)
                ?: return null
            val config = status.javaClass.getMethod("getCodecConfig").invoke(status) ?: return null
            val type = config.javaClass.getMethod("getCodecType").invoke(config) as? Int ?: return null
            val name = when (type) {
                0 -> "SBC"
                1 -> "AAC"
                2 -> "aptX"
                3 -> "aptX HD"
                4 -> "LDAC"
                5 -> "LC3"
                6 -> "Opus"
                else -> return null
            }
            val rate = when (config.javaClass.getMethod("getSampleRate").invoke(config) as? Int) {
                0x01 -> 44_100
                0x02 -> 48_000
                0x04 -> 88_200
                0x08 -> 96_000
                0x10 -> 176_400
                0x20 -> 192_000
                else -> null
            }
            return codecText(name, rate)
        } catch (_: Throwable) {
            return null
        }
    }
}

/** "AAC, 44.1 kHz", or the bare name when the rate is not known. */
internal fun codecText(name: String, rate: Int?): String {
    if (rate == null || rate <= 0) return name
    val kilohertz = if (rate % 1000 == 0) "${rate / 1000}" else "${rate / 1000}.${rate % 1000 / 100}"
    return "$name, $kilohertz kHz"
}

/**
 * Reads the codec of each audio link out of `dumpsys bluetooth_manager`, keyed by the last
 * five characters of the device address ("B4:77"): newer versions of Android print the rest
 * as XX. The format is not a contract and differs between versions; what is not understood
 * is left out.
 */
internal fun parseCodecs(dump: String): Map<String, String> {
    val machine = Regex("""StateMachine for\s+(\S+)""")
    val codec = Regex("""mCodecConfig:\s*\{?\s*codecName:\s*([^,}]+)""")
    val rate = Regex("""mSampleRate:\s*0x[0-9a-fA-F]+\((\d+)\)""")
    val found = HashMap<String, String>()
    var key: String? = null
    for (line in dump.lineSequence()) {
        val header = machine.find(line)
        if (header != null) key = header.groupValues[1].takeLast(5).uppercase()
        val current = key ?: continue
        if (found.containsKey(current)) continue
        val match = codec.find(line) ?: continue
        val name = match.groupValues[1].trim()
        if (name.isEmpty() || name.equals("null", ignoreCase = true)) continue
        val hertz = rate.find(line, match.range.first)?.groupValues?.get(1)?.toIntOrNull()
        found[current] = codecText(name, hertz)
    }
    return found
}
