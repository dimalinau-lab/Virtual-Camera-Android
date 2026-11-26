package com.example.ccamera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_API_URL = "https://api.github.com/repos/dimalinau-lab/Virtual-Camera-Android/releases/latest"
        private val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "api.github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "codeload.github.com"
        )
    }

    private fun isAllowedDownloadUrl(rawUrl: String): Boolean {
        return try {
            val uri = Uri.parse(rawUrl)
            val scheme = uri.scheme?.lowercase() ?: return false
            val host = uri.host?.lowercase() ?: return false
            scheme == "https" && ALLOWED_DOWNLOAD_HOSTS.any { host == it || host.endsWith(".$it") }
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApkPackageAndSignature(apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                android.content.pm.PackageManager.GET_SIGNATURES
            }
            val installedInfo = pm.getPackageInfo(context.packageName, flags)
            val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return false
            if (archiveInfo.packageName != context.packageName) {
                Log.e(TAG, "Blocked APK with mismatched packageName: ${archiveInfo.packageName}")
                return false
            }

            fun extractDigests(info: android.content.pm.PackageInfo): Set<String> {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.signingInfo?.apkContentsSigners ?: emptyArray()
                } else {
                    info.signatures ?: emptyArray()
                }
                return sigs.map { sig ->
                    md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
                }.toSet()
            }

            val installedDigests = extractDigests(installedInfo)
            val archiveDigests = extractDigests(archiveInfo)
            val match = installedDigests.isNotEmpty() && archiveDigests.any { it in installedDigests }
            if (!match) {
                Log.e(TAG, "Blocked APK with untrusted signing certificate")
            }
            match
        } catch (e: Exception) {
            Log.e(TAG, "APK verification error", e)
            false
        }
    }

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val currentVersion: String,
        val latestVersion: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val apkSize: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    fun checkForUpdate(callback: (UpdateInfo?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(GITHUB_API_URL)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "VirtualCam-Android-Updater")
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                if (conn.responseCode != 200) {
                    Log.w(TAG, "GitHub API returned code: ${conn.responseCode}")
                    withContext(Dispatchers.Main) { callback(null) }
                    return@launch
                }

                val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(responseBody)
                val rawTag = json.optString("tag_name", "").trim()
                val latestVersion = if (rawTag.startsWith("v", ignoreCase = true)) rawTag.substring(1) else rawTag
                val bodyNotes = json.optString("body", "Улучшения производительности и стабильности.")

                var downloadUrl = ""
                var apkSize = 0L

                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                val currentVersion = BuildConfig.VERSION_NAME
                val isNewer = isNewerVersion(latestVersion, currentVersion)

                val updateInfo = UpdateInfo(
                    hasUpdate = isNewer && downloadUrl.isNotEmpty(),
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    releaseNotes = bodyNotes,
                    downloadUrl = downloadUrl,
                    apkSize = apkSize
                )

                withContext(Dispatchers.Main) {
                    callback(updateInfo)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking updates: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback(null)
                }
            }
        }
    }

    fun downloadAndInstall(
        updateInfo: UpdateInfo,
        onProgress: (Int, Long, Long) -> Unit,
        onError: (String) -> Unit,
        onPermissionRequired: (() -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!isAllowedDownloadUrl(updateInfo.downloadUrl)) {
                    withContext(Dispatchers.Main) { onError("Недоверенный источник загрузки обновления") }
                    return@launch
                }

                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val targetFile = File(updatesDir, "VirtualCam-v${updateInfo.latestVersion}.apk")
                if (targetFile.exists()) {
                    targetFile.delete()
                }

                var currentUrl = updateInfo.downloadUrl
                var connection: HttpURLConnection? = null
                var redirectCount = 0

                // Поддержка HTTP 301 / 302 редиректов (только HTTPS и разрешенные домены GitHub)
                while (redirectCount < 5) {
                    if (!isAllowedDownloadUrl(currentUrl)) {
                        withContext(Dispatchers.Main) { onError("Заблокирован редирект на недоверенный домен") }
                        return@launch
                    }
                    val url = URL(currentUrl)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "VirtualCam-Android-Updater")
                        connectTimeout = 15000
                        readTimeout = 15000
                    }
                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == 307 || code == 308
                    ) {
                        currentUrl = connection.getHeaderField("Location") ?: break
                        connection.disconnect()
                        redirectCount++
                    } else {
                        break
                    }
                }

                if (connection == null || connection.responseCode != 200) {
                    withContext(Dispatchers.Main) {
                        onError("Ошибка подключения к серверу (${connection?.responseCode ?: -1})")
                    }
                    return@launch
                }

                val totalLength = connection.contentLengthLong.let { if (it > 0) it else updateInfo.apkSize }
                var totalDownloaded = 0L

                connection.inputStream.use { input: InputStream ->
                    FileOutputStream(targetFile).use { output: FileOutputStream ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        var lastProgressReport = 0

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalDownloaded += bytesRead

                            if (totalLength > 0) {
                                val progress = ((totalDownloaded * 100) / totalLength).toInt()
                                if (progress != lastProgressReport) {
                                    lastProgressReport = progress
                                    withContext(Dispatchers.Main) {
                                        onProgress(progress, totalDownloaded, totalLength)
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }
                connection.disconnect()

                if (totalDownloaded <= 0L || (totalLength > 0 && totalDownloaded < (totalLength * 0.95).toLong())) {
                    targetFile.delete()
                    withContext(Dispatchers.Main) { onError("APK загружен не полностью") }
                    return@launch
                }

                if (!verifyApkPackageAndSignature(targetFile)) {
                    targetFile.delete()
                    withContext(Dispatchers.Main) { onError("Ошибка проверки подписи APK") }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    onProgress(100, totalDownloaded, totalLength)
                    installApk(targetFile, onPermissionRequired, onError)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading update: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Сбой скачивания")
                }
            }
        }
    }

    fun installApk(
        apkFile: File,
        onPermissionRequired: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    onPermissionRequired?.invoke()
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer intent: ${e.message}", e)
            onError?.invoke(e.localizedMessage ?: "Не удалось открыть установщик")
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote.isEmpty() || local.isEmpty()) return false
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)

        for (i in 0 until maxLen) {
            val r = if (i < rParts.size) rParts[i] else 0
            val l = if (i < lParts.size) lParts[i] else 0
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
