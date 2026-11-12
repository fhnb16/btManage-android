package ru.fhnb16.btmanage.data

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import ru.fhnb16.btmanage.elevated.Helper
import kotlin.coroutines.resume

/**
 * Rights beyond those of an ordinary application, borrowed from Shizuku or from root.
 *
 * Android lets only system applications connect and disconnect a Bluetooth device. The
 * shell user is allowed to, and so is root. Both can start a program, so the application
 * runs a small part of itself ([Helper]) as one of them.
 */
object Elevated {
    enum class Way { SHIZUKU, ROOT }

    class Output(val exit: Int, val text: String)

    private const val REQUEST = 7301
    private const val ASK_TIMEOUT_MS = 60_000L
    private const val RUN_TIMEOUT_MS = 30_000L

    /** Whether the Shizuku service is up and has reached this application. */
    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    } catch (_: Throwable) {
        false
    }

    fun shizukuGranted(): Boolean = try {
        shizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Asks the user, in Shizuku's own dialog, to let the application use it. */
    suspend fun askShizuku(): Boolean {
        if (!shizukuRunning()) return false
        if (shizukuGranted()) return true
        val granted = withTimeoutOrNull(ASK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val listener = object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode != REQUEST) return
                        Shizuku.removeRequestPermissionResultListener(this)
                        if (continuation.isActive) continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                try {
                    Shizuku.requestPermission(REQUEST)
                } catch (_: Throwable) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }
        return granted == true
    }

    /** The access that can be used right now without asking anybody. */
    fun ready(settings: SettingsData): Way? = when {
        shizukuGranted() -> Way.SHIZUKU
        settings.useRoot -> Way.ROOT
        else -> null
    }

    /** Whether `su` answers. The first call brings up the superuser prompt. */
    fun rootWorks(): Boolean = run(Way.ROOT, "id")?.let { it.exit == 0 && "uid=0" in it.text } == true

    /** The shell command that runs [Helper] out of this application's package. */
    fun helper(context: Context, vararg args: String): String {
        val apk = context.applicationInfo.sourceDir
        val quoted = args.joinToString(" ") { "'" + it.replace("'", "") + "'" }
        return "CLASSPATH='$apk' app_process /system/bin ${Helper::class.java.name} $quoted"
    }

    /** Runs a shell command with the given rights and waits for it. Null when it could not start. */
    fun run(way: Way, command: String): Output? = try {
        val line = "$command 2>&1"
        val process: Process = when (way) {
            Way.ROOT -> Runtime.getRuntime().exec(arrayOf("su", "-c", line))
            Way.SHIZUKU -> shizukuProcess(arrayOf("sh", "-c", line))
        }
        // A superuser prompt nobody answers must not hold the caller for ever.
        val watchdog = Thread {
            try {
                Thread.sleep(RUN_TIMEOUT_MS)
                process.destroy()
            } catch (_: InterruptedException) {
            }
        }
        watchdog.isDaemon = true
        watchdog.start()
        try {
            process.outputStream.close()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            Output(process.waitFor(), text.trim())
        } finally {
            watchdog.interrupt()
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Shizuku keeps its process starter out of the public interface in favour of bound
     * services; for a command that runs once and ends, the starter is the simpler tool.
     */
    private fun shizukuProcess(command: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }
}
