package com.goju.ribs.myrainassist.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One entry of `rain-assist/app/latest.json` — the same file the web "Android 앱 받기" button reads. */
data class LatestApp(
    val versionName: String,
    val versionCode: Int,
    val file: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val url: String get() = AppUpdater.APP_BASE_URL + file

    companion object {
        /** Null when a field is missing or the file name could escape the app folder. */
        fun parse(body: String): LatestApp? = runCatching {
            val json = JSONObject(body)
            val file = json.getString("file")
            if (!SAFE_FILE_NAME.matches(file)) return null
            LatestApp(
                versionName = json.getString("versionName"),
                versionCode = json.getInt("versionCode"),
                file = file,
                sizeBytes = json.getLong("sizeBytes"),
                sha256 = json.getString("sha256").lowercase(),
            )
        }.getOrNull()

        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]+\\.apk")
    }
}

/**
 * Sideload self-update: the app is handed out as an APK from S3 (not a store), so it checks
 * latest.json itself, downloads a newer build, checks its SHA-256 and hands it to the system
 * installer. Android still asks the user to confirm, and only installs over this app when the new
 * APK carries the same signing key (the 0.4+ release key).
 */
object AppUpdater {

    const val APP_BASE_URL = "https://d8dfs01bak16j.cloudfront.net/rain-assist/app/"
    private const val TAG = "AppUpdater"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val UPDATE_DIR = "updates"

    fun isNewer(latest: LatestApp, currentVersionCode: Long): Boolean = latest.versionCode > currentVersionCode

    suspend fun fetchLatest(): LatestApp? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(APP_BASE_URL + "latest.json").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.useCaches = false
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                LatestApp.parse(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchLatest failed", e)
            null
        }
    }

    /**
     * Downloads [latest] into the app's cache and returns the file once its SHA-256 matches, or
     * null on any failure. [onProgress] gets 0..1 (on the IO thread).
     */
    suspend fun download(context: Context, latest: LatestApp, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, latest.file)
            try {
                val connection = URL(latest.url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                    val total = connection.contentLengthLong.takeIf { it > 0 } ?: latest.sizeBytes
                    val digest = MessageDigest.getInstance("SHA-256")
                    connection.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                done += n
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actual != latest.sha256) {
                        Log.w(TAG, "download: sha256 mismatch ($actual != ${latest.sha256})")
                        target.delete()
                        return@withContext null
                    }
                    target
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                Log.w(TAG, "download failed", e)
                target.delete()
                null
            }
        }

    /** Whether the user has allowed this app to install apps ("출처를 알 수 없는 앱 설치"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionSettingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Opens the system installer for [apk]; the user confirms there. */
    fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Leftover APKs from an update that already went in. */
    fun clearDownloads(context: Context) {
        File(context.cacheDir, UPDATE_DIR).listFiles()?.forEach { it.delete() }
    }
}
