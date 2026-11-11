package ru.fhnb16.btmanage.data

import btmanage.core.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.fhnb16.btmanage.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** A release newer than the installed version. */
data class UpdateInfo(val version: String, val pageUrl: String, val apkUrl: String?)

/**
 * Looks for a newer release among the GitHub releases of this application's repository.
 * The core compares the versions and picks the APK. The request carries nothing but a fixed
 * client name. Installing is left to the user: the download opens in the browser.
 */
object Updates {
    /** Null when there is nothing newer, or when GitHub cannot be reached. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!Native.available || BuildConfig.UPDATE_REPOSITORY.isEmpty()) return@withContext null
        runCatching {
            val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "btManage/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val release = try {
                if (connection.responseCode != 200) return@runCatching null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val found = Native.updateCheck(BuildConfig.VERSION_NAME, release, ".apk") ?: return@runCatching null
            val json = JSONObject(found)
            UpdateInfo(
                version = json.getString("version"),
                pageUrl = json.getString("page_url"),
                apkUrl = json.optJSONObject("asset")?.getString("url"),
            )
        }.getOrNull()
    }
}
