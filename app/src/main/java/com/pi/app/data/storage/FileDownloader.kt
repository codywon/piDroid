package com.pi.app.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.pi.app.network.PiApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * 文件下载与本地持久化存储器。
 *
 * 采用流式写入（64KB 缓冲区），避免大文件下载产生高内存占用或 OOM。
 * - Android 10+ (API 29+): 写入 MediaStore.Downloads (RELATIVE_PATH = "Download/πDroid")
 * - Android 9 及以下 (API 26-28): 写入 Environment.DIRECTORY_DOWNLOADS/πDroid
 */
object FileDownloader {

    /**
     * 净化文件名，替换操作系统保留的非法字符。
     */
    fun sanitizeFileName(fileName: String, fallback: String = "download"): String {
        val cleaned = fileName.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim('_')
            .trim()
        return if (cleaned.isBlank()) fallback else cleaned
    }

    /**
     * 根据扩展名猜测 MIME 类型。
     */
    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /**
     * 流式复制并汇报进度。
     */
    private fun copyStreamWithProgress(
        input: InputStream,
        output: OutputStream,
        totalLength: Long,
        onProgress: ((Int) -> Unit)?
    ) {
        val buffer = ByteArray(64 * 1024)
        var bytesCopied = 0L
        var lastPercent = -1

        while (true) {
            val bytes = input.read(buffer)
            if (bytes <= 0) break
            output.write(buffer, 0, bytes)
            bytesCopied += bytes
            if (totalLength > 0 && onProgress != null) {
                val percent = ((bytesCopied * 100) / totalLength).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
        }
        output.flush()
    }

    /**
     * 将远端文件流式下载到系统公共「下载」目录 (Download/πDroid)。
     */
    suspend fun downloadToDownloads(
        context: Context,
        apiClient: PiApiClient,
        remotePath: String,
        displayFileName: String,
        sessionId: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val safeName = sanitizeFileName(displayFileName)
        val mimeType = getMimeType(safeName)
        val downloadUrl = apiClient.fileApiUrl(remotePath, "download", sessionId)
        if (downloadUrl.isBlank()) {
            return@withContext Result.failure(IOException("未配置网关下载地址"))
        }

        val requestBuilder = Request.Builder().url(downloadUrl).get()
        val authHeaders = apiClient.authHeaders()
        for ((k, v) in authHeaders) {
            requestBuilder.header(k, v)
        }

        val resolver = context.contentResolver

        try {
            apiClient.fileOkHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("下载失败 (HTTP ${resp.code})"))
                }
                val body = resp.body ?: return@withContext Result.failure(IOException("响应体为空"))
                val contentLength = body.contentLength()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/πDroid")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }

                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: return@withContext Result.failure(IOException("无法在系统下载目录创建文件"))

                    try {
                        body.byteStream().use { input ->
                            resolver.openOutputStream(uri)?.use { output ->
                                copyStreamWithProgress(input, output, contentLength, onProgress)
                            } ?: throw IOException("无法写入目标输出流")
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                        Result.success("Download/πDroid/$safeName")
                    } catch (e: Exception) {
                        runCatching { resolver.delete(uri, null, null) }
                        throw e
                    }
                } else {
                    val targetDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "πDroid"
                    )
                    if (!targetDir.exists() && !targetDir.mkdirs()) {
                        return@withContext Result.failure(IOException("无法创建下载目录"))
                    }

                    // 避免同名覆盖
                    var targetFile = File(targetDir, safeName)
                    if (targetFile.exists()) {
                        val baseName = safeName.substringBeforeLast('.')
                        val ext = safeName.substringAfterLast('.', "")
                        val extSuffix = if (ext.isNotEmpty()) ".$ext" else ""
                        targetFile = File(targetDir, "${baseName}_${System.currentTimeMillis()}$extSuffix")
                    }

                    try {
                        body.byteStream().use { input ->
                            FileOutputStream(targetFile).use { output ->
                                copyStreamWithProgress(input, output, contentLength, onProgress)
                            }
                        }
                        Result.success(targetFile.absolutePath)
                    } catch (e: Exception) {
                        runCatching { targetFile.delete() }
                        throw e
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 将远端文件流式下载到用户指定的 SAF [Uri]（如通过 ACTION_CREATE_DOCUMENT 选取）。
     */
    suspend fun downloadToUri(
        context: Context,
        apiClient: PiApiClient,
        remotePath: String,
        targetUri: Uri,
        sessionId: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val downloadUrl = apiClient.fileApiUrl(remotePath, "download", sessionId)
        if (downloadUrl.isBlank()) {
            return@withContext Result.failure(IOException("未配置网关下载地址"))
        }

        val requestBuilder = Request.Builder().url(downloadUrl).get()
        for ((k, v) in apiClient.authHeaders()) {
            requestBuilder.header(k, v)
        }

        try {
            apiClient.fileOkHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("下载失败 (HTTP ${resp.code})"))
                }
                val body = resp.body ?: return@withContext Result.failure(IOException("响应体为空"))
                val contentLength = body.contentLength()

                context.contentResolver.openOutputStream(targetUri)?.use { output ->
                    body.byteStream().use { input ->
                        copyStreamWithProgress(input, output, contentLength, onProgress)
                    }
                } ?: return@withContext Result.failure(IOException("无法打开目标 Uri 输出流"))
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
