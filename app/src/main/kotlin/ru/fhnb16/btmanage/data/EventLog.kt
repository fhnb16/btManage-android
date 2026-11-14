package ru.fhnb16.btmanage.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The detailed log: what the application hears and does, a line each, written to a file
 * while the user has it switched on (Settings, About). The file can be handed to any
 * application through Android's share menu, together with the diagnostics text.
 *
 * The lines are technical and in English, like the diagnostics, so that they can go into a
 * report as they are.
 */
object EventLog {
    private const val DIR = "logs"
    private const val FILE = "btmanage.log"
    private const val OLD_FILE = "btmanage.1.log"

    /** A file larger than this is moved aside and a new one begun. */
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val RECENT_LINES = 300

    @Volatile
    private var dir: File? = null

    /** Writes happen one after another, away from the caller. */
    private val writer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "event-log").apply { isDaemon = true } }
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    private val recentLines = MutableStateFlow<List<String>>(emptyList())

    /** The last lines, newest last, for the console of the developer mode. */
    val recent: StateFlow<List<String>> = recentLines

    fun open(context: Context) {
        dir = File(context.applicationContext.filesDir, DIR)
    }

    val enabled: Boolean get() = Settings.current.verboseLog

    /**
     * Notes one line under a [topic] ("scan", "aap", "gatt", "console" and so on). Lines are
     * kept only while the detailed log is on, except for the console, whose lines the user
     * asked for by sending a command.
     */
    fun note(topic: String, text: String) {
        val console = topic == "console"
        if (!enabled && !console) return
        val line = "${time.format(Date())} [$topic] $text"
        recentLines.value = (recentLines.value + line).takeLast(RECENT_LINES)
        if (!enabled) return
        val folder = dir ?: return
        writer.execute {
            try {
                folder.mkdirs()
                val file = File(folder, FILE)
                if (file.length() > MAX_BYTES) {
                    val old = File(folder, OLD_FILE)
                    old.delete()
                    file.renameTo(old)
                }
                File(folder, FILE).appendText(line + "\n")
            } catch (_: Throwable) {
            }
        }
    }

    /** Bytes as "04 00 1A", at most [limit] of them. */
    fun hex(bytes: ByteArray, limit: Int = 64): String =
        bytes.take(limit).joinToString(" ") { "%02X".format(it) } + if (bytes.size > limit) " … (${bytes.size} bytes)" else ""

    /** The size of the log on disk, in bytes. */
    fun size(): Long {
        val folder = dir ?: return 0
        return File(folder, FILE).length() + File(folder, OLD_FILE).length()
    }

    fun clear() {
        val folder = dir ?: return
        recentLines.value = emptyList()
        writer.execute {
            File(folder, FILE).delete()
            File(folder, OLD_FILE).delete()
        }
    }

    /**
     * Android's share menu with the log: the diagnostics text first, then the older file,
     * then the current one, joined into one file in the cache that the share hands out.
     * Null when there is nothing to share.
     */
    fun shareIntent(context: Context): Intent? {
        val folder = dir ?: return null
        val out = File(context.cacheDir, "share").apply { mkdirs() }
        val bundle = File(out, "btmanage-log.txt")
        try {
            bundle.writeText(Diagnostics.report() + "\n\n")
            for (name in listOf(OLD_FILE, FILE)) {
                val file = File(folder, name)
                if (file.exists()) bundle.appendText(file.readText())
            }
        } catch (_: Throwable) {
            return null
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", bundle)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "btManage log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }
}
