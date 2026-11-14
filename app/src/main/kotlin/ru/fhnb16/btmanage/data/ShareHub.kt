package ru.fhnb16.btmanage.data

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.OpenableColumns
import btmanage.core.Native
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/** Another btManage on the local network. */
data class Peer(val id: String, val name: String, val platform: String, val address: String)

/** Something sent or received, for the history on the screen. */
data class ShareItem(
    /** "received", "sent" or "failed". */
    val type: String,
    /** "file" or "text". */
    val kind: String,
    /** The other side: the name of the sender, or the address sent to. */
    val other: String,
    val name: String,
    val size: Long,
    val path: String?,
    val text: String?,
    val error: String?,
    val at: Long,
)

/**
 * Files and text between btManage applications on one local network, through the core's
 * `btmanage-share`: this device announces itself, hears the others, and takes items while
 * the user lets it. Runs from the first use until the process ends.
 *
 * Written without a run on a device.
 */
object ShareHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val peersFlow = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = peersFlow

    private val itemsFlow = MutableStateFlow<List<ShareItem>>(emptyList())

    /** Newest first. */
    val items: StateFlow<List<ShareItem>> = itemsFlow

    private val runningFlow = MutableStateFlow(false)
    val running: StateFlow<Boolean> = runningFlow

    @Volatile
    private var handle = 0L
    private var multicast: WifiManager.MulticastLock? = null
    private var app: Context? = null

    /** The name other devices see. */
    var name: String = Build.MODEL
        private set

    /** Starts taking part, if it has not already. */
    @Synchronized
    fun start(context: Context) {
        if (handle != 0L || !Native.available) return
        val application = context.applicationContext
        app = application
        name = deviceName(application)
        val inbox = File(application.filesDir, INBOX).apply { mkdirs() }
        val started = try {
            Native.shareStart(installationId(application), name, "android", inbox.absolutePath, true)
        } catch (_: UnsatisfiedLinkError) {
            0L
        }
        if (started == 0L) {
            EventLog.note("share", "could not start")
            return
        }
        handle = started
        runningFlow.value = true
        Native.shareSetReceiving(started, Settings.current.shareReceive)
        // Android drops broadcasts on Wi-Fi to save power unless an application holds this.
        multicast = try {
            application.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("btmanage-share")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (_: Throwable) {
            null
        }
        EventLog.note("share", "started on port ${Native.sharePort(started)}")
        scope.launch { poll() }
    }

    fun setReceiving(on: Boolean) {
        val current = handle
        if (current != 0L) Native.shareSetReceiving(current, on)
    }

    /** Where other devices can reach this one by hand: "ip:port" for each address it has. */
    fun addresses(): List<String> {
        val current = handle
        if (current == 0L) return emptyList()
        val port = Native.sharePort(current)
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { "${it.hostAddress}:$port" }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun sendText(to: String, text: String) {
        val current = handle
        if (current != 0L && to.isNotBlank() && text.isNotEmpty()) Native.shareSendText(current, to.trim(), text)
    }

    /**
     * Sends a file the user picked. Its content is copied into the cache first, under its
     * own name, since the core reads files by path.
     */
    suspend fun sendFile(context: Context, to: String, uri: Uri) = withContext(Dispatchers.IO) {
        val current = handle
        if (current == 0L || to.isBlank()) return@withContext
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: "file"
        val outbox = File(context.cacheDir, OUTBOX).apply { mkdirs() }
        val copy = File(outbox, name.replace('/', '_'))
        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
        }.getOrNull()
        if (copied == null) {
            add(ShareItem("failed", "file", to, name, 0, null, null, "could not read the file", System.currentTimeMillis()))
            return@withContext
        }
        Native.shareSendFile(current, to.trim(), copy.absolutePath)
    }

    private suspend fun poll() {
        while (scope.isActive && handle != 0L) {
            val current = handle
            runCatching { Native.sharePeers(current) }.getOrNull()?.let { json ->
                peersFlow.value = runCatching { parsePeers(json) }.getOrDefault(peersFlow.value)
            }
            runCatching { Native.shareEvents(current) }.getOrNull()?.let { json ->
                runCatching { parseEvents(json) }.getOrNull()?.forEach(::add)
            }
            delay(POLL_MS)
        }
    }

    private fun add(item: ShareItem) {
        EventLog.note("share", "${item.type} ${item.kind} ${item.name} ${item.other} ${item.error.orEmpty()}")
        itemsFlow.value = (listOf(item) + itemsFlow.value).take(MAX_ITEMS)
    }

    private fun parsePeers(json: String): List<Peer> {
        val array = JSONArray(json)
        return List(array.length()) { index ->
            val peer = array.getJSONObject(index)
            Peer(peer.getString("id"), peer.getString("name"), peer.getString("platform"), peer.getString("address"))
        }
    }

    private fun parseEvents(json: String): List<ShareItem> {
        val array = JSONArray(json)
        val now = System.currentTimeMillis()
        return List(array.length()) { index ->
            val event = array.getJSONObject(index)
            val type = event.getString("type")
            ShareItem(
                type = type,
                kind = event.optString("kind", "file"),
                other = if (type == "received") event.optString("from") else event.optString("to"),
                name = event.optString("name"),
                size = event.optLong("size"),
                path = if (event.isNull("path")) null else event.optString("path").takeIf { it.isNotEmpty() },
                text = if (event.isNull("text")) null else event.optString("text").takeIf { it.isNotEmpty() },
                error = event.optString("error").takeIf { it.isNotEmpty() },
                at = now,
            )
        }
    }

    /** A stable identifier of this installation, so the others can tell it from another. */
    private fun installationId(context: Context): String {
        val prefs = context.getSharedPreferences("share", Context.MODE_PRIVATE)
        return prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }

    /** The name the user gave the phone, else its model. */
    private fun deviceName(context: Context): String =
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    /** Where received files lie; shared with other applications through the file provider. */
    const val INBOX = "inbox"
    private const val OUTBOX = "outbox"
    private const val POLL_MS = 1_000L
    private const val MAX_ITEMS = 100
}
