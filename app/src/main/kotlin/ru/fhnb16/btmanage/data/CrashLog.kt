package ru.fhnb16.btmanage.data

import android.content.Context
import ru.fhnb16.btmanage.BuildConfig
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Keeps the last crash of the application in a file, so that the diagnostics page can show
 * it after the restart. Android itself shows the user nothing but "the app has stopped".
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val MAX_LENGTH = 8_000

    @Volatile
    private var dir: File? = null

    /** Call once, as the process starts. Android's own handling of the crash goes on as before. */
    fun install(context: Context) {
        val files = context.applicationContext.filesDir
        dir = files
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val time = DateFormat.getDateTimeInstance().format(Date())
                val text = "$time, ${BuildConfig.VERSION_NAME}, thread ${thread.name}\n" + error.stackTraceToString()
                File(files, FILE).writeText(text.take(MAX_LENGTH))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash, its time and stack trace, or null when there has been none. */
    fun last(): String? {
        val files = dir ?: return null
        return try {
            File(files, FILE).takeIf { it.exists() }?.readText()
        } catch (_: Throwable) {
            null
        }
    }
}
