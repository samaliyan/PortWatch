package com.samaliyan.portwatch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val apkUrl: String, val notes: String)

/** Checks GitHub Releases for a newer version, downloads it and opens the installer. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/samaliyan/PortWatch/releases/latest"

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Newest release if it is newer than this app, otherwise null. Throws on network errors. */
    suspend fun findUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PortWatch")
        }
        try {
            if (conn.responseCode != 200) throw IOException("GitHub returned ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val version = json.getString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets")
            var url: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {
                        url = a.getString("browser_download_url")
                        break
                    }
                }
            }
            if (url != null && isNewer(version, currentVersion)) {
                UpdateInfo(version, url, json.optString("body", "").trim())
            } else null
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Downloads the APK, reporting progress from 0 to 1 (or -1 when the size is unknown). */
    suspend fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "PortWatch-${info.version}.apk")
            val tmp = File(dir, "download.tmp")
            var url = URL(info.apkUrl)
            var conn: HttpURLConnection
            // GitHub redirects to its file server; follow manually to be safe.
            var hops = 0
            while (true) {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "PortWatch")
                }
                val code = conn.responseCode
                if (code in 300..399 && hops < 5) {
                    val next = conn.getHeaderField("Location") ?: throw IOException("Bad redirect")
                    conn.disconnect()
                    url = URL(url, next)
                    hops++
                    continue
                }
                if (code != 200) throw IOException("Download failed ($code)")
                break
            }
            try {
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            withContext(Dispatchers.Main) {
                                onProgress(if (total > 0) done.toFloat() / total else -1f)
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw IOException("Download incomplete")
            } finally {
                conn.disconnect()
            }
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not save update")
            file
        }

    /** True if Android lets PortWatch open the installer; otherwise the user must allow it first. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun allowInstallIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
