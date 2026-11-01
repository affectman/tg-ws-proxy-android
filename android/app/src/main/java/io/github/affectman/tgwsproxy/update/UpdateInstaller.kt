package io.github.affectman.tgwsproxy.update

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import io.github.affectman.tgwsproxy.BuildConfig
import io.github.affectman.tgwsproxy.data.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads a release APK and hands it to the system installer. */
object UpdateInstaller {

    sealed interface Result {
        /** The user must first allow this app to install apps. */
        data object NeedsPermission : Result
        data object Launched : Result
        data class Failed(val reason: String) : Result
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun downloadAndInstall(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): Result {
        if (!canInstall(context)) return Result.NeedsPermission
        val apk = try {
            download(context, info.apkUrl, onProgress)
        } catch (e: IOException) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: return Result.Failed("not an APK")
        if (archive.packageName != context.packageName) return Result.Failed("wrong package ${archive.packageName}")
        if (PackageInfoCompat.getLongVersionCode(archive) <= BuildConfig.VERSION_CODE) {
            return Result.Failed("not newer")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Result.Launched
        } catch (e: Exception) {
            Result.Failed(e.message ?: "no installer")
        }
    }

    private suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "update.apk")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "tg-ws-proxy-android")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            target
        }
}
