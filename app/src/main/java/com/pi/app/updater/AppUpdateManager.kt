package com.pi.app.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.pi.app.data.storage.AppPreferences
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
import java.security.MessageDigest
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
    val isManual: Boolean = false,
    /** Release 附带的 checksums.txt 地址；用于下载后校验 APK 的 SHA-256。 */
    val checksumsUrl: String? = null,
    /** GitHub asset 自带 digest（形如 sha256:...）；无 checksums.txt 时的兜底。 */
    val expectedSha256: String? = null
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

    /** CI 随 Release 一同发布的 sha256 清单文件名。 */
    private const val CHECKSUMS_ASSET_NAME = "checksums.txt"

    /** 低于此体积的下载结果不可能是有效 APK（多半是 404 错误页）。 */
    private const val MIN_APK_BYTES = 3L * 1024 * 1024

    // ---- 下载调优参数 ----------------------------------------------------
    /** 读写缓冲区大小（字节）：下载落盘与 APK 哈希校验共用。 */
    private const val IO_BUFFER_BYTES = 64 * 1024
    /** 低速看门狗的观察窗口（秒）：连上后在这个窗口内评估平均速率。 */
    private const val WATCHDOG_WINDOW_SECONDS = 4.0
    /** 看门狗速率下限（KB/s），低于它认为该通道不可用、平滑换线。 */
    private const val WATCHDOG_MIN_SPEED_KBPS = 100.0
    /** 已下载超过该比例后不再判速率，避免尾部小文件被误判为慢速。 */
    private const val WATCHDOG_PROGRESS_GUARD = 0.95
    /** 瞬时速率采样间隔（秒）。 */
    private const val SPEED_SAMPLE_INTERVAL_SECONDS = 0.4
    /** 进度回调最小间隔（毫秒），避免刷爆 UI 线程。 */
    private const val PROGRESS_UI_INTERVAL_MS = 120L
    /** 测速探测的超时（毫秒）。 */
    private const val RANK_PROBE_TIMEOUT_MS = 1500L
    /** 测速探测拉取的分块大小（字节）。 */
    private const val RANK_PROBE_CHUNK_BYTES = 4096

    private const val HEX_CHARS = "0123456789abcdef"
    private val CHECKSUM_WHITESPACE = Regex("\\s+")

    /** 第三方镜像源。默认不启用：仅当用户显式开启后才会参与下载竞速。 */
    private data class MirrorSource(
        val prefix: String,
        val label: String,
        /** 测速排序时的额外惩罚，用于给已知限速的公益镜像降权。 */
        val speedPenaltyMs: Long = 0L
    )

    private val MIRROR_SOURCES = listOf(
        MirrorSource("https://ghfast.top/", "国内极速专线 (ghfast)"),
        MirrorSource("https://ghproxy.net/", "国内备用镜像 (ghproxy)"),
        // 全网共用单连接限速严重，给 350ms 惩罚权重
        MirrorSource("https://gh-proxy.com/", "公益兜底镜像 (gh-proxy)", speedPenaltyMs = 350L)
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
            val rawNotes = json.optString("body", "• 优化智能体交互与稳定性").trim()
            val releaseNotes = cleanChangelogMarkdown(rawNotes)
            val publishedAt = json.optString("published_at", "").take(10)

            val currentVersion = getCurrentVersionName(context)
            if (!isNewerVersion(currentVersion, tagName)) {
                return@withContext UpdateCheckResult.NoUpdate(currentVersion)
            }

            if (!isManual && isVersionIgnored(context, tagName)) {
                return@withContext UpdateCheckResult.Ignored(tagName)
            }

            val assets = json.optJSONArray("assets")
            var targetDownloadUrl = ""
            var targetFileSize = 0L
            var checksumsUrl: String? = null
            var expectedSha256: String? = null

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val assetName = asset.optString("name", "")

                    // CI 随包发布的 sha256sum 清单
                    if (assetName.equals(CHECKSUMS_ASSET_NAME, ignoreCase = true)) {
                        checksumsUrl = asset.optString("browser_download_url", "").takeIf { it.isNotBlank() }
                        continue
                    }
                    if (!assetName.endsWith(".apk", ignoreCase = true)) continue

                    if (assetName.contains("release", ignoreCase = true) || targetDownloadUrl.isEmpty()) {
                        targetDownloadUrl = asset.optString("browser_download_url", "")
                        targetFileSize = asset.optLong("size", 0L)
                        // GitHub 对较新上传的 asset 提供 "sha256:<hex>" 形式的 digest
                        expectedSha256 = asset.optString("digest", "")
                            .takeIf { it.startsWith("sha256:", ignoreCase = true) }
                            ?.substringAfter(':')
                            ?.trim()
                            ?.lowercase(Locale.US)
                            ?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
                        if (assetName.contains("release", ignoreCase = true)) {
                            break
                        }
                    }
                }
            }

            if (targetDownloadUrl.isBlank()) {
                return@withContext UpdateCheckResult.Error("未在 Release 中找到适用的 APK 安装包")
            }

            val mirrorUrls = MIRROR_SOURCES.map { it.prefix + targetDownloadUrl }
            val updateInfo = UpdateInfo(
                tagName = tagName,
                versionName = tagName.removePrefix("v").removePrefix("V"),
                releaseTitle = if (releaseTitle.isNotBlank()) releaseTitle else "piDroid $tagName",
                releaseNotes = releaseNotes,
                downloadUrl = targetDownloadUrl,
                mirrorUrls = mirrorUrls,
                fileSize = targetFileSize,
                publishedAt = publishedAt,
                isManual = isManual,
                checksumsUrl = checksumsUrl,
                expectedSha256 = expectedSha256
            )

            setLastCheckTimestamp(context, System.currentTimeMillis())
            UpdateCheckResult.HasUpdate(updateInfo)
        } catch (e: Exception) {
            Log.e(TAG, "checkUpdate error", e)
            UpdateCheckResult.Error(e.localizedMessage ?: "检查更新异常，请检查网络连接")
        }
    }

    /**
     * 规范化 Release Notes：剔除与更新无关的下载说明、宣传标语，只保留简洁的更新与修复条目
     */
    private fun cleanChangelogMarkdown(raw: String): String {
        if (raw.isBlank()) return "- 优化智能体连接稳定性与界面交互体验"
        val lines = raw.lines()
        val filtered = mutableListOf<String>()
        var skipSection = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#") && (trimmed.contains("下载") || trimmed.contains("安装") || trimmed.contains("首发版本"))) {
                skipSection = true
                continue
            }
            if (skipSection) {
                if (trimmed.startsWith("#")) {
                    skipSection = false
                } else {
                    continue
                }
            }
            if (trimmed.contains("直接下载下方附件") ||
                trimmed.startsWith("Full Changelog", ignoreCase = true) ||
                trimmed.startsWith("**Full Changelog**", ignoreCase = true) ||
                trimmed.contains("compare/v")
            ) {
                continue
            }
            filtered.add(line)
        }
        val result = filtered.joinToString("\n").trim()
        return result.ifBlank { raw.trim() }
    }

    data class DownloadChannel(
        val name: String,
        val url: String,
        val speedPenaltyMs: Long = 0L
    )

    /**
     * 高速流式下载 APK，支持真实微块测速优选、HTTP 206 断点无感续传、低速熔断自动换线与实时速率计算
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long, speedText: String, channelName: String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(updateDir, "piDroid-update.apk")
        val tempFile = File(updateDir, "piDroid-update.apk.tmp")

        if (targetFile.exists()) {
            targetFile.delete()
        }

        val allCandidates = buildChannels(context, info)

        // 发布方公布的 SHA-256 只解析一次，避免每个通道重试都重拉一遍清单
        val expectedSha256 = resolveExpectedSha256(info)

        // 步骤 1：并发真实微块测速（1500ms），选出真实吞吐最快的通道
        val rankedChannels = rankChannelsBySpeed(allCandidates)
        var lastException: Exception? = null

        for (channel in rankedChannels) {
            try {
                Log.d(TAG, "Attempting download from optimal channel [${channel.name}]: ${channel.url}")
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
                val isResume = existingBytes > 0 && (info.fileSize <= 0 || existingBytes < info.fileSize)

                val reqBuilder = Request.Builder()
                    .url(channel.url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android) piDroid-App")

                if (isResume) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                    Log.d(TAG, "Resuming download from $existingBytes bytes on ${channel.name}")
                }

                val response = httpClient.newCall(reqBuilder.build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    continue
                }

                val code = response.code
                val isPartial = isResume && code == 206
                val append = isPartial
                val actualExistingBytes = if (append) existingBytes else 0L
                if (!append && tempFile.exists()) {
                    tempFile.delete()
                }

                val body = response.body ?: continue
                val totalBytes = if (append) {
                    actualExistingBytes + body.contentLength()
                } else {
                    if (body.contentLength() > 0) body.contentLength() else info.fileSize
                }

                body.byteStream().use { input ->
                    FileOutputStream(tempFile, append).use { output ->
                        val buffer = ByteArray(IO_BUFFER_BYTES)
                        var bytesRead: Int
                        var totalRead = actualExistingBytes
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

                            // 低速熔断看门狗：前 4 秒如果平均速度低于 100 KB/s，主动断开当前通道换下一个（断点续传不丢进度）
                            val elapsedSec = (now - connectionStartTime) / 1000.0
                            if (elapsedSec >= WATCHDOG_WINDOW_SECONDS &&
                                totalRead < totalBytes * WATCHDOG_PROGRESS_GUARD
                            ) {
                                val currentChannelDownloaded = totalRead - actualExistingBytes
                                val avgSpeedKb = (currentChannelDownloaded / 1024.0) / elapsedSec
                                if (avgSpeedKb < WATCHDOG_MIN_SPEED_KBPS) {
                                    Log.w(TAG, "Watchdog triggered: avg speed ${avgSpeedKb.toInt()} KB/s < 100 KB/s on ${channel.name}")
                                    throw IOException("当前通道连接速率过低 (${avgSpeedKb.toInt()} KB/s)，平滑断点切换下一通道...")
                                }
                            }

                            // 采样计算瞬时下载速度（每 400ms 刷新一次速度值）
                            val sampleElapsedSec = (now - speedSampleStartTime) / 1000.0
                            if (sampleElapsedSec >= SPEED_SAMPLE_INTERVAL_SECONDS) {
                                val bytesPerSec = (speedSampleBytes / sampleElapsedSec).toLong()
                                currentSpeedText = formatSpeed(bytesPerSec)
                                speedSampleBytes = 0L
                                speedSampleStartTime = now
                            }

                            // 刷新 UI 进度（约 120ms 一次）
                            if (now - lastProgressUiTime >= PROGRESS_UI_INTERVAL_MS || totalRead == totalBytes) {
                                lastProgressUiTime = now
                                val progress = if (totalBytes > 0) {
                                    (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                withContext(Dispatchers.Main) {
                                    onProgress(progress, totalRead, totalBytes, currentSpeedText, channel.name)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                // 完整性校验 0/3：体积下限过滤（防止把 HTML 404 错误页当成 APK 保存）
                if (!tempFile.exists() || tempFile.length() <= MIN_APK_BYTES) {
                    tempFile.delete()
                    continue
                }

                // 完整性校验 1/3：SHA-256 与 Release 公布值比对，不匹配则丢弃并换通道
                val hashProblem = verifyDownloadedHash(expectedSha256, tempFile)
                if (hashProblem != null) {
                    Log.e(TAG, "Integrity check failed on ${channel.name}: $hashProblem")
                    tempFile.delete()
                    lastException = SecurityException(hashProblem)
                    continue
                }

                // 完整性校验 2/3：签名必须与已安装应用一致（防第三方镜像/中间人投放伪造包）
                if (!verifyApkSignature(context, tempFile)) {
                    Log.e(TAG, "APK signature mismatch on ${channel.name}, refusing to keep the file")
                    tempFile.delete()
                    lastException = SecurityException(
                        "签名不匹配，已丢弃；请改用浏览器下载或官方 Release"
                    )
                    continue
                }

                if (targetFile.exists()) targetFile.delete()
                if (tempFile.renameTo(targetFile)) {
                    Log.d(TAG, "Download completed from ${channel.name}, size=${targetFile.length()}")
                    return@withContext Result.success(targetFile)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download channel [${channel.name}] encounter issue: ${e.message}")
                lastException = e
            }
        }

        Result.failure(lastException ?: Exception("所有加速下载镜像通道均无法访问，建议点击【改用 Chrome / 浏览器下载】"))
    }

    /**
     * 候选下载通道：官方直连永远排第一。第三方镜像默认关闭，
     * 仅当用户在设置里显式开启后才参与竞速。
     */
    private fun buildChannels(context: Context, info: UpdateInfo): List<DownloadChannel> {
        val channels = mutableListOf(DownloadChannel("官方直连 (GitHub)", info.downloadUrl))
        val mirrorsEnabled = AppPreferences(context).useUpdateMirrors
        if (mirrorsEnabled) {
            MIRROR_SOURCES.forEachIndexed { index, mirror ->
                val url = info.mirrorUrls.getOrNull(index) ?: (mirror.prefix + info.downloadUrl)
                channels.add(DownloadChannel(mirror.label, url, mirror.speedPenaltyMs))
            }
        }
        return channels
    }

    /**
     * 校验下载文件的 SHA-256。返回 null 表示通过，否则返回失败原因。
     * 若 Release 既无 checksums.txt 也无 asset digest，则记录告警后放行，
     * 仍由强制执行的签名校验兜底。
     */
    private fun verifyDownloadedHash(expected: String?, file: File): String? {
        if (expected.isNullOrBlank()) {
            Log.w(TAG, "No published SHA-256 for this release; relying on signature verification")
            return null
        }
        val actual = sha256Hex(file) ?: return "无法计算安装包校验值"
        if (!actual.equals(expected, ignoreCase = true)) {
            return "安装包校验失败（期望 ${expected.take(16)}…，实际 ${actual.take(16)}…），文件可能已被篡改"
        }
        Log.d(TAG, "SHA-256 verified")
        return null
    }

    /** 优先用 asset digest；缺失时拉取 checksums.txt 解析。 */
    private fun resolveExpectedSha256(info: UpdateInfo): String? {
        if (!info.expectedSha256.isNullOrBlank()) return info.expectedSha256
        val url = info.checksumsUrl ?: return null
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "piDroid-Mobile-App")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "checksums.txt returned HTTP ${response.code}")
                    return null
                }
                parseChecksums(response.body?.string(), info.downloadUrl.substringAfterLast('/'))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch checksums.txt: ${e.message}")
            null
        }
    }

    /** 解析 `sha256sum` 输出（`<64hex>  <filename>`），只认文件名完全匹配的条目。 */
    private fun parseChecksums(text: String?, targetFileName: String): String? {
        if (text.isNullOrBlank() || targetFileName.isBlank()) return null
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(CHECKSUM_WHITESPACE, limit = 2)
            if (parts.size < 2) continue
            val hash = parts[0].lowercase(Locale.US)
            if (hash.length != 64 || !hash.all { it in "0123456789abcdef" }) continue
            val fileName = parts[1].trim().removePrefix("*").substringAfterLast('/')
            if (fileName == targetFileName) return hash
        }
        return null
    }

    private fun sha256Hex(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(IO_BUFFER_BYTES)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    } catch (e: Exception) {
        Log.e(TAG, "sha256Hex(file) failed", e)
        null
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
        }
        return out.toString()
    }

    /**
     * 校验待安装包与当前已安装应用的签名是否一致。
     * 包名不符或证书集不符即拒绝——这是防止伪造更新包的最后一道闸门。
     */
    fun verifyApkSignature(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() <= MIN_APK_BYTES) {
            Log.e(TAG, "verifyApkSignature: file missing or too small")
            return false
        }
        return try {
            val pm = context.packageManager
            val archive = readPackageArchiveInfo(pm, apkFile)
            if (archive == null) {
                Log.e(TAG, "verifyApkSignature: cannot read package archive")
                return false
            }
            if (archive.packageName != context.packageName) {
                Log.e(TAG, "verifyApkSignature: package ${archive.packageName} != ${context.packageName}")
                return false
            }
            val apkCerts = archiveSigningFingerprints(archive)
            @Suppress("DEPRECATION")
            val installedCerts = pm
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures
                .signingFingerprints()
            if (apkCerts.isEmpty() || installedCerts.isEmpty()) {
                Log.e(TAG, "verifyApkSignature: no signing certificates available")
                return false
            }
            apkCerts == installedCerts
        } catch (e: Exception) {
            Log.e(TAG, "verifyApkSignature failed", e)
            false
        }
    }

    private fun readPackageArchiveInfo(pm: PackageManager, apkFile: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        }
    }

    private fun archiveSigningFingerprints(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.signingInfo?.apkContentsSigners.signingFingerprints()
        }
        @Suppress("DEPRECATION")
        return info.signatures.signingFingerprints()
    }

    private fun Array<Signature>?.signingFingerprints(): Set<String> {
        if (this == null) return emptySet()
        return mapNotNull { signature ->
            try {
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hash a signing certificate", e)
                null
            }
        }.toSet()
    }

    private suspend fun rankChannelsBySpeed(candidates: List<DownloadChannel>): List<DownloadChannel> = withContext(Dispatchers.IO) {
        try {
            val scoredList = candidates.map { channel ->
                async {
                    val start = System.currentTimeMillis()
                    val isAlive = try {
                        // 用带 Range 的 GET 真实拉一小块，杜绝纯 HEAD 握手的假象与 405 拦截
                        val req = Request.Builder()
                            .url(channel.url)
                            .header("Range", "bytes=0-${RANK_PROBE_CHUNK_BYTES - 1}")
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android) piDroid-App")
                            .build()
                        val client = httpClient.newBuilder()
                            .connectTimeout(RANK_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(RANK_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .build()
                        val resp = client.newCall(req).execute()
                        val code = resp.code
                        val success = resp.isSuccessful && (code == 200 || code == 206)
                        if (success) {
                            resp.body?.byteStream()?.use { stream ->
                                val buf = ByteArray(RANK_PROBE_CHUNK_BYTES)
                                stream.read(buf) // 真实读取接收数据流
                            }
                        } else {
                            resp.close()
                        }
                        success
                    } catch (_: Exception) {
                        false
                    }
                    val duration = System.currentTimeMillis() - start
                    Pair(channel, if (isAlive) (duration + channel.speedPenaltyMs) else Long.MAX_VALUE)
                }
            }.awaitAll()

            // 测速成功的优先按真实耗时从低到高排列，超时的放在最后
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
     * 智能调用外部浏览器下载 APK（逃生通道）
     * 优先定向调起 Google Chrome 浏览器，规避国产 ROM 应用商店拦截；若未安装 Chrome 则调起系统默认浏览器。
     */
    fun openInBrowser(context: Context, url: String) {
        // 先尝试定向拉起 Google Chrome
        try {
            val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pm = context.packageManager
            if (chromeIntent.resolveActivity(pm) != null) {
                context.startActivity(chromeIntent)
                return
            }
        } catch (_: Exception) {
            // Chrome 未安装或受限，平滑降级
        }

        // 降级为系统标准默认浏览器
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
     * 调用系统安装器安装 APK。启动安装器前强制验签，验不过则拒绝并返回 false。
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!verifyApkSignature(context, apkFile)) {
            Log.e(TAG, "installApk: signature verification failed, aborting install of ${apkFile.absolutePath}")
            return false
        }
        return try {
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            false
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
