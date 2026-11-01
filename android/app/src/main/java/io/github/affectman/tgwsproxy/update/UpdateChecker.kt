package io.github.affectman.tgwsproxy.update

import io.github.affectman.tgwsproxy.BuildConfig
import io.github.affectman.tgwsproxy.data.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the latest GitHub release of this fork.
 *
 * Release tags look like `v1.11.3-android.412`; the number after `android.`
 * is the versionCode (the commit count CI built), so no extra file is needed.
 */
object UpdateChecker {
    private val CODE_IN_TAG = Regex("""android\.(\d+)$""")

    fun versionCodeOf(tag: String): Int? = CODE_IN_TAG.find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Parses a `releases/latest` response; null when it has no APK or a bad tag. */
    fun parseRelease(json: String): UpdateInfo? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val code = versionCodeOf(tag) ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?: return null
        return UpdateInfo(
            versionCode = code,
            versionName = tag.removePrefix("v"),
            apkUrl = apk.getString("browser_download_url"),
            pageUrl = o.optString("html_url"),
            notes = o.optString("body").take(4000),
        )
    }

    /** The latest release if it is newer than this build, else null. */
    suspend fun fetchNewer(): UpdateInfo? = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "tg-ws-proxy-android")
        }
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return@withContext null  // no releases yet
                else -> throw IOException("GitHub answered ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseRelease(body)?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        } finally {
            conn.disconnect()
        }
    }
}
