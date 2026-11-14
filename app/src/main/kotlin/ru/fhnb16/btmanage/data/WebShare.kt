package ru.fhnb16.btmanage.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/** A file this phone offers to browsers. */
data class Offered(val id: String, val uri: Uri, val name: String, val size: Long)

/**
 * A page on the local network for devices without btManage: an iPhone, a Mac, any browser
 * opens it, takes the files this phone offers and sends files and text to it. What comes in
 * lands in the history of the Share screen, as from another btManage.
 *
 * The address carries a random key, so that a device on the network that only guesses the
 * port gets nothing. Nothing is encrypted: plain HTTP, on a network the user trusts. It runs
 * while the user keeps it on and the application's process lives.
 *
 * Written without a run on a device.
 */
object WebShare {
    private val runningFlow = MutableStateFlow(false)
    val running: StateFlow<Boolean> = runningFlow

    private val addressesFlow = MutableStateFlow<List<String>>(emptyList())

    /** The addresses to open in a browser, one per network this phone is on. */
    val addresses: StateFlow<List<String>> = addressesFlow

    private val offeredFlow = MutableStateFlow<List<Offered>>(emptyList())
    val offered: StateFlow<List<Offered>> = offeredFlow

    @Volatile
    private var server: ServerSocket? = null
    private var key = ""
    private var appContext: Context? = null
    private val workers = Executors.newCachedThreadPool()
    private val random = SecureRandom()

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val application = context.applicationContext
        appContext = application
        key = List(KEY_LENGTH) { KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)] }.joinToString("")
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                // The usual port when it is free, so the address stays the same between runs.
                try {
                    bind(InetSocketAddress(PORT))
                } catch (_: Exception) {
                    bind(InetSocketAddress(0))
                }
            }
        } catch (error: Exception) {
            EventLog.note("web", "could not start: ${error.message}")
            return
        }
        server = socket
        runningFlow.value = true
        refreshAddresses()
        EventLog.note("web", "started on port ${socket.localPort}")
        Thread({ accept(socket) }, "web-share").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        runningFlow.value = false
        addressesFlow.value = emptyList()
    }

    /** Looks at the networks again: the addresses change when the phone moves to another one. */
    fun refreshAddresses() {
        val socket = server ?: return
        addressesFlow.value = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { "http://${it.hostAddress}:${socket.localPort}/$key" }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Adds files the user picked to what browsers can take. */
    fun offer(context: Context, uris: List<Uri>) {
        val resolver = context.contentResolver
        val added = uris.mapNotNull { uri ->
            // Keep reading the file after the picker is gone.
            runCatching {
                resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val (name, size) = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        (cursor.getString(0) ?: "file") to (if (cursor.isNull(1)) -1L else cursor.getLong(1))
                    } else {
                        null
                    }
                }
            }.getOrNull() ?: return@mapNotNull null
            Offered(List(8) { KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)] }.joinToString(""), uri, name, size)
        }
        offeredFlow.value = offeredFlow.value + added
    }

    fun withdraw(id: String) {
        offeredFlow.value = offeredFlow.value.filterNot { it.id == id }
    }

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                continue
            }
            workers.execute { serve(client) }
        }
    }

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: InputStream)

    private fun serve(client: Socket) {
        client.use {
            runCatching {
                client.soTimeout = TIMEOUT_MS
                val input = BufferedInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val request = read(input) ?: return
                route(request, output, client.inetAddress?.hostAddress ?: "?")
                output.flush()
            }
        }
    }

    private fun read(input: InputStream): Request? {
        val first = line(input) ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val header = line(input) ?: return null
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon > 0) headers[header.substring(0, colon).trim().lowercase()] = header.substring(colon + 1).trim()
            if (headers.size > MAX_HEADERS) return null
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('='))
        }
        return Request(parts[0], path, query, headers, input)
    }

    /** One line of the request head, without its end; null at the end of the stream or past the limit. */
    private fun line(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (byte == '\n'.code) break
            if (byte != '\r'.code) bytes.write(byte)
            if (bytes.size() > MAX_LINE) return null
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }

    private fun route(request: Request, output: OutputStream, from: String) {
        val prefix = "/$key"
        if (key.isEmpty() || !(request.path == prefix || request.path.startsWith("$prefix/"))) {
            respond(output, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
            return
        }
        val rest = request.path.removePrefix(prefix).trimEnd('/')
        when {
            request.method == "GET" && rest.isEmpty() -> respond(output, 200, "text/html; charset=utf-8", page().toByteArray())
            request.method == "GET" && rest.startsWith("/file/") -> sendFile(output, rest.removePrefix("/file/"))
            request.method == "POST" && rest == "/upload" -> receiveFile(request, output, from)
            request.method == "POST" && rest == "/text" -> receiveText(request, output, from)
            else -> respond(output, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
        }
    }

    private fun sendFile(output: OutputStream, id: String) {
        val context = appContext
        val file = offeredFlow.value.firstOrNull { it.id == id }
        val stream = file?.let { runCatching { context?.contentResolver?.openInputStream(it.uri) }.getOrNull() }
        if (file == null || stream == null) {
            respond(output, 404, "text/plain; charset=utf-8", "This file is no longer offered".toByteArray())
            return
        }
        stream.use { input ->
            val head = StringBuilder()
                .append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: application/octet-stream\r\n")
                .append("Content-Disposition: attachment; filename=\"${ascii(file.name)}\"; filename*=UTF-8''${encode(file.name)}\r\n")
                .apply { if (file.size >= 0) append("Content-Length: ${file.size}\r\n") }
                .append("Connection: close\r\n\r\n")
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            input.copyTo(output, BUFFER)
        }
    }

    private fun receiveFile(request: Request, output: OutputStream, from: String) {
        val context = appContext ?: return
        val length = request.headers["content-length"]?.toLongOrNull()
        if (length == null || length < 0) {
            respond(output, 411, "text/plain; charset=utf-8", "The size of the file is missing".toByteArray())
            return
        }
        val name = safeName(request.query["name"].orEmpty())
        val inbox = File(context.filesDir, ShareHub.INBOX).apply { mkdirs() }
        val target = unique(inbox, name)
        var left = length
        val buffer = ByteArray(BUFFER)
        target.outputStream().use { file ->
            while (left > 0) {
                val read = request.body.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) break
                file.write(buffer, 0, read)
                left -= read
            }
        }
        if (left > 0) {
            target.delete()
            ShareHub.record(ShareItem("failed", "file", browser(from), name, length, null, null, "the upload stopped", false, System.currentTimeMillis()))
            respond(output, 400, "text/plain; charset=utf-8", "The upload stopped".toByteArray())
            return
        }
        ShareHub.record(ShareItem("received", "file", browser(from), target.name, length, target.absolutePath, null, null, false, System.currentTimeMillis()))
        respond(output, 200, "text/plain; charset=utf-8", "OK".toByteArray())
    }

    private fun receiveText(request: Request, output: OutputStream, from: String) {
        val length = request.headers["content-length"]?.toIntOrNull()
        if (length == null || length < 0 || length > MAX_TEXT) {
            respond(output, 413, "text/plain; charset=utf-8", "The text is too long".toByteArray())
            return
        }
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = request.body.read(bytes, read, length - read)
            if (count < 0) break
            read += count
        }
        val text = String(bytes, 0, read, Charsets.UTF_8)
        if (text.isNotEmpty()) {
            ShareHub.record(ShareItem("received", "text", browser(from), "", text.length.toLong(), null, text, null, false, System.currentTimeMillis()))
        }
        respond(output, 200, "text/plain; charset=utf-8", "OK".toByteArray())
    }

    private fun browser(from: String) = "Browser · $from"

    private fun respond(output: OutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
    }

    /** The page: the files offered, then sending a file or text to this phone. */
    private fun page(): String {
        val phone = html(ShareHub.name)
        val files = offeredFlow.value
        val list = if (files.isEmpty()) {
            "<p class=\"note\">Nothing offered yet. Add files on the phone under Share, Browser</p>"
        } else {
            files.joinToString("", "<ul>", "</ul>") { file ->
                val size = if (file.size >= 0) "<span class=\"note\">${size(file.size)}</span>" else ""
                "<li><a href=\"/$key/file/${file.id}\" download>${html(file.name)}</a>$size</li>"
            }
        }
        return PAGE
            .replace("{phone}", phone)
            .replace("{list}", list)
            .replace("{key}", key)
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "${bytes shr 10} KB"
        else -> "$bytes B"
    }

    private fun html(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun decode(text: String) = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun ascii(text: String) = text.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")

    /** A file name without a path in it. */
    private fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').trim().trimStart('.').ifEmpty { "file" }.take(MAX_NAME)

    /** [name] in [directory], with a number added when a file of that name is there already. */
    private fun unique(directory: File, name: String): File {
        var candidate = File(directory, name)
        var number = 2
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (candidate.exists()) {
            candidate = File(directory, "$stem ($number)$extension")
            number++
        }
        return candidate
    }

    private const val PORT = 8080
    private const val KEY_LENGTH = 6
    private const val KEY_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    private const val TIMEOUT_MS = 30_000
    private const val BUFFER = 64 * 1024
    private const val MAX_LINE = 8 * 1024
    private const val MAX_HEADERS = 64
    private const val MAX_TEXT = 1024 * 1024
    private const val MAX_NAME = 200

    /**
     * The page, in English, as browsers on other systems show it. One accent, the system's
     * font, light or dark with the browser. {phone}, {list} and {key} are filled in.
     */
    private val PAGE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{phone} · btManage</title>
<style>
:root { --bg: #f7f7f8; --card: #ffffff; --text: #1b1b1f; --muted: #5d5e66; --line: #e2e2e6; --accent: #0b6bcb; }
@media (prefers-color-scheme: dark) {
  :root { --bg: #121214; --card: #1d1d20; --text: #e6e6ea; --muted: #a0a0a8; --line: #2e2e33; --accent: #6aa8f0; }
}
* { box-sizing: border-box; }
body { margin: 0; padding: 24px 16px; background: var(--bg); color: var(--text);
  font: 16px/1.5 -apple-system, system-ui, "Segoe UI", Roboto, sans-serif; }
main { max-width: 560px; margin: 0 auto; display: grid; gap: 16px; }
h1 { font-size: 24px; font-weight: 600; margin: 0; }
h2 { font-size: 16px; font-weight: 600; margin: 0 0 8px; }
section { background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 16px; }
.note { color: var(--muted); font-size: 14px; }
p { margin: 0; }
ul { list-style: none; margin: 0; padding: 0; }
li { display: flex; justify-content: space-between; gap: 12px; padding: 8px 0; border-top: 1px solid var(--line); }
li:first-child { border-top: 0; }
a { color: var(--accent); text-decoration: none; overflow-wrap: anywhere; }
textarea { width: 100%; min-height: 96px; padding: 8px; border: 1px solid var(--line); border-radius: 8px;
  background: var(--bg); color: var(--text); font: inherit; resize: vertical; }
.row { display: flex; gap: 8px; align-items: center; margin-top: 8px; flex-wrap: wrap; }
button, label.button { appearance: none; border: 0; border-radius: 8px; padding: 8px 16px; font: inherit;
  background: var(--accent); color: #fff; cursor: pointer; }
input[type=file] { display: none; }
</style>
</head>
<body>
<main>
<h1>{phone}</h1>
<section>
<h2>Files from this phone</h2>
{list}
</section>
<section>
<h2>Send to this phone</h2>
<div class="row">
<label class="button" for="files">Choose files</label>
<input id="files" type="file" multiple>
<span id="progress" class="note"></span>
</div>
<textarea id="text" placeholder="Text"></textarea>
<div class="row"><button id="send">Send text</button><span id="sent" class="note"></span></div>
</section>
<p class="note">Over plain HTTP on this network. Anyone on it with this address can use the page while it is on</p>
</main>
<script>
const base = "/{key}";
const progress = document.getElementById("progress");
document.getElementById("files").addEventListener("change", async (event) => {
  const files = Array.from(event.target.files);
  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    const ok = await new Promise((done) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", base + "/upload?name=" + encodeURIComponent(file.name));
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) progress.textContent = file.name + ": " + Math.round(e.loaded * 100 / e.total) + "%";
      };
      xhr.onload = () => done(xhr.status === 200);
      xhr.onerror = () => done(false);
      xhr.send(file);
    });
    progress.textContent = ok ? (i + 1) + " of " + files.length + " sent" : file.name + " was not sent";
    if (!ok) break;
  }
  event.target.value = "";
});
document.getElementById("send").addEventListener("click", async () => {
  const text = document.getElementById("text").value;
  if (!text) return;
  const sent = document.getElementById("sent");
  try {
    const answer = await fetch(base + "/text", { method: "POST", body: text });
    sent.textContent = answer.ok ? "Sent" : "Not sent";
    if (answer.ok) document.getElementById("text").value = "";
  } catch (e) {
    sent.textContent = "Not sent";
  }
});
</script>
</body>
</html>
""".trimIndent()
}
