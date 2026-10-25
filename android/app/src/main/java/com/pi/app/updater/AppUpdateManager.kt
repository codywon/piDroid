package com.pi.app.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 应用版本信息数据模型
 */
data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val mirrorUrls: List<String>,
    val fileSize: Long,
    val publishedAt: String,
    val isManual: Boolean = false
)

/**
 * 更新检查状态枚举与密封类
 */
sealed class UpdateCheckResult {
    data class HasUpdate(val info: UpdateInfo) : UpdateCheckResult()
    data class NoUpdate(val currentVersion: String) : UpdateCheckResult()
    data class Ignored(val tagName: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * 工业级 GitHub Release 应用内全自动更新管理引擎 (参考 Termius 架构)
 *
 * 核心特性：
 * 1. 自动语义化版本号比对（SemVer）；
 * 2. 5大国内高速开源镜像智能多通道并行测速竞速（选择最优路径）；
 * 3. 低速看门狗熔断自动换线（防止慢速节点卡死）；
 * 4. 实时瞬时速率采样计算与流式进度回传；
 * 5. Android 8.0 ~ 15 未知应用安全安装权限与 FileProvider 沙盒免存储权限暴露；
 * 6. 支持静默检测防打扰与“忽略此版本”持久化。
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_REPO_OWNER = "codywon"
    private const val GITHUB_REPO_NAME = "piDroid"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"

    private const val PREFS_NAME = "pidroid_app_update_prefs"
    private const val KEY_IGNORED_TAG = "key_ignored_version_tag"
    private const val KEY_LAST_CHECK_TIME = "key_last_check_timestamp"

    // 国内知名稳定开源 GitHub Release 下载加速镜像站
    private val MIRROR_PREFIXES = listOf(
        "https://ghfast.top/",
        "https://ghp.ci/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/"
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 获取当前 App 的 versionName（如 "1.0.0"）
     */
    fun getCurrentVersionName(context: Context): String {
        return try {
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pi.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    /**
     * 语义化版本号比对：判断 remote 是否大于 current
     * 例如："1.0.1" > "1.0.0" => true; "1.1.0" > "1.0.9" => true
     */
    fun isNewerVersion(current: String, remote: String): Boolean {
        val curClean = current.trim().removePrefix("v").removePrefix("V")
        val remClean = remote.trim().removePrefix("v").removePrefix("V")

        val curParts = curClean.split(".").mapNotNull { it.toIntOrNull() }
        val remParts = remClean.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(curParts.size, remParts.size)
        for (i in 0 until maxLen) {
            val curVal = curParts.getOrElse(i) { 0 }
            val remVal = remParts.getOrElse(i) { 0 }
            if (remVal > curVal) return true
            if (remVal < curVal) return false
        }
        return false
    }

    /**
     * 检查 GitHub 是否有新版本
     */
    suspend fun checkUpdate(context: Context, isManual: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "piDroid-Mobile-App")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                return@withContext UpdateCheckResult.Error("获取更新失败 (HTTP $code)")
            }

            val bodyStr = response.body?.string() ?: return@withContext UpdateCheckResult.Error("服务器响应为空")
            val json = JSONObject(bodyStr)
            val tagName = json.optString("tag_name", "").trim()
            val releaseTitle = json.optString("name", tagName).trim()
            val releaseNotes = json.optString("body", "• 优化智能体交互与稳定性").trim()
            val publishedAt = json.optString("published_at", "").take(10)

            val currentVersion = getCurrentVersionName(context)
            if (!isNewerVersion(currentVersion, tagName)) {
                return@withContext UpdateCheckResult.NoUpdate(currentVersion)
            }

            // 如果不是手动触发且用户已设置忽略此版本，则静默跳过
            if (!isManual && isVersionIgnored(context, tagName)) {
                return@withContext UpdateCheckResult.Ignored(tagName)
            }

            // 精准匹配 APK 下载链接（优先 release 签名包）
            val assets = json.optJSONArray("assets")
            var targetDownloadUrl = ""
            var targetFileSize = 0L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        if (assetName.contains("release", ignoreCase = true) || targetDownloadUrl.isEmpty()) {
                            targetDownloadUrl = asset.optString("browser_download_url", "")
                            targetFileSize = asset.optLong("size", 0L)
                            if (assetName.contains("release", ignoreCase = true)) {
                                break
                            }
                        }
                    }
                }
            }

            if (targetDownloadUrl.isBlank()) {
                return@withContext UpdateCheckResult.Error("未在 Release 中找到适用的 APK 安装包")
            }

            val mirrorUrls = MIRROR_PREFIXES.map { "$it$targetDownloadUrl" }
            val updateInfo = UpdateInfo(
                tagName = tagName,
                versionName = tagName.removePrefix("v").removePrefix("V"),
                releaseTitle = if (releaseTitle.isNotBlank()) releaseTitle else "piDroid $tagName",
                releaseNotes = releaseNotes,
                downloadUrl = targetDownloadUrl,
                mirrorUrls = mirrorUrls,
                fileSize = targetFileSize,
                publishedAt = publishedAt,
                isManual = isManual
            )

            setLastCheckTimestamp(context, System.currentTimeMillis())
            UpdateCheckResult.HasUpdate(updateInfo)
        } catch (e: Exception) {
            Log.e(TAG, "checkUpdate error", e)
            UpdateCheckResult.Error(e.localizedMessage ?: "检查更新异常，请检查网络连接")
        }
    }

    data class DownloadChannel(
        val name: String,
        val url: String
    )

    /**
     * 高速流式下载 APK，支持通道并发竞速测速、低速熔断自动换线与实时速率计算
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long, speedText: String, channelName: String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(updateDir, "piDroid-update.apk")
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val allCandidates = mutableListOf<DownloadChannel>().apply {
            add(DownloadChannel("官方直连 (GitHub Direct)", info.downloadUrl))
            add(DownloadChannel("国内极速镜像 (ghfast)", "https://ghfast.top/${info.downloadUrl}"))
            add(DownloadChannel("国内极速镜像 (ghp.ci)", "https://ghp.ci/${info.downloadUrl}"))
            add(DownloadChannel("国内加速镜像 (gh-proxy)", "https://gh-proxy.com/${info.downloadUrl}"))
            add(DownloadChannel("国内备用镜像 (ghproxy)", "https://ghproxy.net/${info.downloadUrl}"))
        }

        // 步骤 1：轻量并发测速竞速（1200ms），选出响应最快的通道排在首位
        val rankedChannels = rankChannelsBySpeed(allCandidates)
        var lastException: Exception? = null

        for (channel in rankedChannels) {
            try {
                Log.d(TAG, "Attempting download from optimal channel [${channel.name}]: ${channel.url}")
                val request = Request.Builder()
                    .url(channel.url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android) piDroid-App")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    continue
                }

                val body = response.body ?: continue
                val contentLength = if (body.contentLength() > 0) body.contentLength() else info.fileSize

                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastProgressUiTime = 0L
                        var speedSampleStartTime = System.currentTimeMillis()
                        var speedSampleBytes = 0L
                        var currentSpeedText = "-- KB/s"
                        val connectionStartTime = System.currentTimeMillis()

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            speedSampleBytes += bytesRead

                            val now = System.currentTimeMillis()

                            // 低速熔断看门狗：前 4 秒如果平均速度低于 120 KB/s，主动断开当前通道换更快的通道
                            val elapsedSec = (now - connectionStartTime) / 1000.0
                            if (elapsedSec >= 4.0 && totalRead < contentLength * 0.9) {
                                val avgSpeedKb = (totalRead / 1024.0) / elapsedSec
                                if (avgSpeedKb < 120.0) {
                                    Log.w(TAG, "Watchdog triggered: speed ${avgSpeedKb.toInt()} KB/s < 120 KB/s on ${channel.name}")
                                    throw IOException("当前通道连接速率过低 (${avgSpeedKb.toInt()} KB/s)，自动切换更优镜像通道")
                                }
                            }

                            // 采样计算瞬时下载速度（每 400ms 刷新一次速度值）
                            val sampleElapsedSec = (now - speedSampleStartTime) / 1000.0
                            if (sampleElapsedSec >= 0.4) {
                                val bytesPerSec = (speedSampleBytes / sampleElapsedSec).toLong()
                                currentSpeedText = formatSpeed(bytesPerSec)
                                speedSampleBytes = 0L
                                speedSampleStartTime = now
                            }

                            // 刷新 UI 进度（约 120ms 一次）
                            if (now - lastProgressUiTime >= 120 || totalRead == contentLength) {
                                lastProgressUiTime = now
                                val progress = if (contentLength > 0) {
                                    (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                withContext(Dispatchers.Main) {
                                    onProgress(progress, totalRead, contentLength, currentSpeedText, channel.name)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                // 完整性校验：下载后的文件必须大于 3MB（防止把 HTML 404 错误页当成 APK 保存）
                if (targetFile.exists() && targetFile.length() > 3 * 1024 * 1024) {
                    Log.d(TAG, "Download successfully completed from ${channel.name}, size=${targetFile.length()}")
                    return@withContext Result.success(targetFile)
                } else {
                    targetFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download channel [${channel.name}] failed: ${e.message}")
                lastException = e
                if (targetFile.exists()) targetFile.delete()
            }
        }

        Result.failure(lastException ?: Exception("所有加速下载镜像通道均无法访问，建议点击【用浏览器下载】"))
    }

    private suspend fun rankChannelsBySpeed(candidates: List<DownloadChannel>): List<DownloadChannel> = withContext(Dispatchers.IO) {
        try {
            val scoredList = candidates.map { channel ->
                async {
                    val start = System.currentTimeMillis()
                    val isAlive = try {
                        val req = Request.Builder()
                            .url(channel.url)
                            .head()
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android) piDroid-App")
                            .build()
                        val client = httpClient.newBuilder()
                            .connectTimeout(1200, TimeUnit.MILLISECONDS)
                            .readTimeout(1200, TimeUnit.MILLISECONDS)
                            .build()
                        client.newCall(req).execute().use { it.isSuccessful }
                    } catch (_: Exception) {
                        false
                    }
                    val duration = System.currentTimeMillis() - start
                    Pair(channel, if (isAlive) duration else Long.MAX_VALUE)
                }
            }.awaitAll()

            // 测速成功的优先按延迟从低到高排列，超时的放在最后
            scoredList.sortedBy { it.second }.map { it.first }
        } catch (_: Exception) {
            candidates
        }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> "${bytesPerSec / 1024} KB/s"
            else -> "$bytesPerSec B/s"
        }
    }

    /**
     * 调用系统外部浏览器下载 APK（逃生通道）
     */
    fun openInBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open browser", e)
        }
    }

    /**
     * 检查是否有安装未知应用权限（Android 8.0+）
     */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * 创建引导用户开启安装未知应用权限的系统设置 Intent
     */
    fun createInstallPermissionIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * 调用系统安装器安装 APK
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
        }
    }

    /**
     * 忽略指定版本（持久化记录）
     */
    fun ignoreVersion(context: Context, tagName: String) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_IGNORED_TAG, tagName).apply()
    }

    /**
     * 检查某个版本是否已被用户忽略
     */
    fun isVersionIgnored(context: Context, tagName: String): Boolean {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ignored = sp.getString(KEY_IGNORED_TAG, null)
        return ignored == tagName
    }

    private fun setLastCheckTimestamp(context: Context, timestamp: Long) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putLong(KEY_LAST_CHECK_TIME, timestamp).apply()
    }
}
