package ru.fhnb16.btmanage.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import btmanage.core.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.fhnb16.btmanage.BuildConfig
import ru.fhnb16.btmanage.R
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** A release newer than the installed version. */
data class UpdateInfo(
    val version: String,
    val pageUrl: String,
    /** The APK of the release, if it has one. */
    val apkUrl: String?,
    /** SHA-256 of the APK as lowercase hex, when GitHub reports it. */
    val sha256: String?,
    val size: Long,
)

/** Where the updater stands. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState

    /** Downloaded and handed to Android, which installs it or asks the user first. */
    data class Installing(val info: UpdateInfo) : UpdateState

    /** The check failed ([info] is null) or the download or the installation did. */
    data class Failed(val info: UpdateInfo?) : UpdateState
}

/** The release a state is about, if any. */
val UpdateState.release: UpdateInfo?
    get() = when (this) {
        is UpdateState.Available -> this.info
        is UpdateState.Downloading -> this.info
        is UpdateState.Installing -> this.info
        is UpdateState.Failed -> this.info
        else -> null
    }

/**
 * Keeps the application up to date from the GitHub releases of its own repository.
 *
 * The core compares the versions and picks the APK. The application downloads it, checks it
 * against the SHA-256 GitHub reports, and hands it to Android's package installer. Android
 * itself refuses an APK that is not signed with the key of the installed application.
 *
 * Android asks the user twice the first time: to let this application install packages at
 * all, and to confirm the installation. From Android 12 on, later updates of the
 * application by itself need no confirmation.
 *
 * The requests to GitHub carry nothing but a fixed client name.
 */
object Updates {
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 2

    private val current = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = current

    private val busy = AtomicBoolean(false)

    /** Whether an activity of the application is on screen, so Android's dialog may be opened. */
    @Volatile
    var foreground = false

    /** Whether Android lets this application install packages. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system screen where the user lets this application install packages. */
    fun permissionIntent(context: Context): Intent = Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    /** Whether the connection is one where a download costs the user money or quota. */
    fun isMetered(context: Context): Boolean =
        runCatching { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }
            .getOrDefault(true)

    /** Asks GitHub for the latest release. Returns it if it is newer than what is installed. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!Native.available || BuildConfig.UPDATE_REPOSITORY.isEmpty()) return@withContext null
        // A download in progress knows its release already.
        if (busy.get()) return@withContext current.value.release

        current.value = UpdateState.Checking
        val found = runCatching { fetch() }
        val info = found.getOrNull()
        current.value = when {
            found.isFailure -> UpdateState.Failed(null)
            info == null -> UpdateState.UpToDate
            else -> UpdateState.Available(info)
        }
        info
    }

    /**
     * Downloads the release found by [check] and hands it to Android to install. Returns
     * false when there is nothing to install or the download failed; the state says which.
     */
    suspend fun install(context: Context): Boolean = withContext(Dispatchers.IO) {
        val info = current.value.release
        val url = info?.apkUrl
        if (info == null || url == null || !busy.compareAndSet(false, true)) return@withContext false

        val app = context.applicationContext
        try {
            current.value = UpdateState.Downloading(info, 0)
            val directory = File(app.cacheDir, "update").apply {
                deleteRecursively()
                mkdirs()
            }
            val apk = File(directory, "update.apk")
            download(info, url, apk)
            current.value = UpdateState.Installing(info)
            commit(app, apk)
            true
        } catch (_: Exception) {
            current.value = UpdateState.Failed(info)
            false
        } finally {
            busy.set(false)
        }
    }

    /** Takes the result of an installation from Android. Called by [InstallReceiver]. */
    fun onInstallStatus(context: Context, intent: Intent) {
        val info = current.value.release
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants the user to confirm. Its dialog can be opened only while the
                // application is on screen; otherwise a notification leads to it.
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    current.value = UpdateState.Failed(info)
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val opened = foreground && runCatching { context.startActivity(confirm) }.isSuccess
                if (!opened) notifyConfirm(context, confirm, info)
            }
            // The process is usually replaced before this arrives.
            PackageInstaller.STATUS_SUCCESS -> current.value = UpdateState.Idle
            else -> current.value = UpdateState.Failed(info)
        }
    }

    private fun fetch(): UpdateInfo? {
        val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")
        val connection = open(url)
        val release = try {
            // No release yet is not a failure.
            if (connection.responseCode == 404) return null
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val found = Native.updateCheck(BuildConfig.VERSION_NAME, release, ".apk") ?: return null
        val json = JSONObject(found)
        val asset = json.optJSONObject("asset")
        return UpdateInfo(
            version = json.getString("version"),
            pageUrl = json.getString("page_url"),
            apkUrl = asset?.getString("url"),
            sha256 = asset?.takeIf { !it.isNull("sha256") }?.getString("sha256"),
            size = asset?.optLong("size") ?: 0L,
        )
    }

    private fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "btManage/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
    }

    /** Downloads the APK, reporting progress, and checks it against the published SHA-256. */
    private fun download(info: UpdateInfo, url: String, target: File) {
        val connection = open(URL(url))
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.size
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = 0
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (percent != shown) {
                            shown = percent
                            current.value = UpdateState.Downloading(info, percent)
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && actual != info.sha256) {
                throw IOException("The download does not match the published checksum")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Hands the APK to Android's package installer. The result arrives at [InstallReceiver]. */
    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // An application updating itself may skip the confirmation, once Android trusts it.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            // Android adds the result to this intent, so it has to be mutable.
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val result = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            )
            session.commit(result.intentSender)
        }
    }

    /** A notification that opens Android's confirmation, for when the application is not on screen. */
    private fun notifyConfirm(context: Context, confirm: Intent, info: UpdateInfo?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.update_ready, info?.version.orEmpty()))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}

/** Receives the result of an installation from Android's package installer. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updates.onInstallStatus(context, intent)
}
