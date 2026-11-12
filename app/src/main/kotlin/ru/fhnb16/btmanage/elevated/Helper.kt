package ru.fhnb16.btmanage.elevated

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Looper
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/**
 * The part of the application that runs as the shell user or as root, started with
 * `app_process` by [ru.fhnb16.btmanage.data.Elevated]. It is not inside the application
 * here: there is no `Context`, no permissions of the package, only the rights of whoever
 * started it.
 *
 * Commands: `probe`, `connect <address>`, `disconnect <address>`, `metadata`. The answer
 * ends in one line, `ok` or `error: ...`, and the exit code says the same.
 *
 * Written without a run on a device.
 */
object Helper {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            run(args)
        } catch (error: Throwable) {
            println("error: " + describe(error))
            1
        }
        System.out.flush()
        exitProcess(code)
    }

    private fun run(args: Array<String>): Int {
        val command = args.firstOrNull()
        if (command == "probe") {
            println("ok uid=" + android.os.Process.myUid())
            return 0
        }
        if ((command == "connect" || command == "disconnect") && args.size >= 2) {
            val failure = requestLink(adapter(), args[1], command == "connect")
            println(if (failure == null) "ok" else "error: $failure")
            return if (failure == null) 0 else 1
        }
        if (command == "metadata") return metadata(adapter())
        println("error: unknown command")
        return 2
    }

    /**
     * Prints what Android has on record for each paired device, a line per device:
     * `device <address>|<key>=<value>|...`. Android keeps the levels of single earbuds and
     * of the case there, and the maker, model and firmware, for the devices that told it:
     * through Fast Pair, mostly. Only privileged callers may read it.
     */
    @SuppressLint("MissingPermission")
    private fun metadata(adapter: BluetoothAdapter): Int {
        val read = BluetoothDevice::class.java.getMethod("getMetadata", Int::class.javaPrimitiveType)
        var failure: String? = null
        for (device in adapter.bondedDevices) {
            val line = StringBuilder("device ").append(device.address)
            for (key in METADATA_KEYS) {
                val value = try {
                    read.invoke(device, key) as? ByteArray
                } catch (error: Throwable) {
                    failure = describe(error)
                    null
                }
                if (value == null || value.isEmpty()) continue
                val text = String(value, Charsets.UTF_8).map { if (it < ' ' || it == '|') ' ' else it }.joinToString("")
                line.append('|').append(key).append('=').append(text.trim())
            }
            println(line)
        }
        println(if (failure == null) "ok" else "error: $failure")
        return if (failure == null) 0 else 1
    }

    /**
     * Keys of `BluetoothDevice.getMetadata`: maker, model, software and hardware version;
     * levels of the left earbud, the right one and the case, then whether each is
     * charging; level of a single battery and whether it is charging.
     */
    private val METADATA_KEYS = intArrayOf(0, 1, 2, 3, 10, 11, 12, 13, 14, 15, 18, 19)

    @Suppress("DEPRECATION")
    private fun adapter(): BluetoothAdapter {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        // An application has the Bluetooth module introduced to the framework when it
        // starts. Here nobody did that yet.
        try {
            Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null)
        } catch (_: Throwable) {
        }
        return BluetoothAdapter.getDefaultAdapter() ?: error("no Bluetooth adapter")
    }
}

/**
 * Asks Android to connect or disconnect every profile of a paired device. Returns null
 * when Android took the request, otherwise the reason it did not.
 *
 * Only a privileged caller gets through; an ordinary application is refused, which is the
 * answer the caller of this function is looking for when it tries without extra rights.
 */
@SuppressLint("MissingPermission")
internal fun requestLink(adapter: BluetoothAdapter, address: String, connect: Boolean): String? {
    val device = adapter.getRemoteDevice(address)
    val failures = ArrayList<String>()

    // Android 13 and later: BluetoothDevice.connect() and disconnect(), which answer with a
    // status code, zero for success.
    try {
        val result = BluetoothDevice::class.java.getMethod(if (connect) "connect" else "disconnect").invoke(device)
        if (result == 0) return null
        failures += "status $result"
    } catch (error: Throwable) {
        failures += describe(error)
    }

    // Android 11 and 12: the same request lives on the adapter.
    val name = if (connect) "connectAllEnabledProfiles" else "disconnectAllEnabledProfiles"
    try {
        val result = BluetoothAdapter::class.java.getMethod(name, BluetoothDevice::class.java).invoke(adapter, device)
        if (result == true) return null
        failures += "$name: $result"
    } catch (error: Throwable) {
        failures += describe(error)
    }

    return failures.joinToString("; ")
}

/** The cause of a failed reflective call, in one line. */
internal fun describe(error: Throwable): String {
    val cause = (error as? InvocationTargetException)?.targetException ?: error
    val message = cause.message
    return if (message.isNullOrEmpty()) cause.javaClass.simpleName else cause.javaClass.simpleName + ": " + message
}
