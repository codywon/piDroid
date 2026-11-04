package com.pi.app.data.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.pi.app.network.PiApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class SessionExportResult(
    val displayPath: String,
    val shareIntent: Intent
)

/**
 * 会话导出为 HTML 文件助手。
 */
object SessionExporter {

    /**
     * 生成合法且易读的导出 HTML 文件名。
     * 格式：pi-session-${title或id}.html
     */
    fun exportFileName(sessionId: String, title: String?): String {
        val rawName = title?.takeIf { it.isNotBlank() } ?: sessionId.take(8)
        val clean = rawName.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .replace(Regex("\\s+"), "-")
            .trim('_', '-')
            .take(50)
        val finalName = if (clean.isBlank()) sessionId.take(8) else clean
        return "pi-session-$finalName.html"
    }

    /**
     * 构建唤起系统原生 Sharesheet 的分享 Intent。
     */
    fun buildShareIntent(context: Context, htmlFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            htmlFile
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, htmlFile.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 流式下载会话 HTML 并持久化存入系统「下载」目录 (Download/πDroid/)，
     * 同时构建就绪的分享 Intent。
     */
    suspend fun exportSessionHtml(
        context: Context,
        apiClient: PiApiClient,
        sessionId: String,
        title: String?
    ): Result<SessionExportResult> = withContext(Dispatchers.IO) {
        val fileName = exportFileName(sessionId, title)
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val tempFile = File(exportDir, fileName)

        // 1. 从网关流式下载到临时文件
        val downloadResult = apiClient.downloadSessionExportHtml(sessionId, tempFile)
        if (downloadResult.isFailure) {
            return@withContext Result.failure(
                downloadResult.exceptionOrNull() ?: IOException("下载失败")
            )
        }

        // 2. 存入系统公共下载目录 (Download/πDroid/)
        val displayPath = "Download/πDroid/$fileName"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/πDroid")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    tempFile.inputStream().use { input ->
                        resolver.openOutputStream(uri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
            } else {
                val pubDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "πDroid"
                )
                pubDir.mkdirs()
                val pubFile = File(pubDir, fileName)
                tempFile.copyTo(pubFile, overwrite = true)
            }
        } catch (e: Exception) {
            // 公共存储写入失败不阻断分享
            android.util.Log.w("SessionExporter", "Failed to save to public Downloads", e)
        }

        // 3. 构建分享面板
        val shareIntent = buildShareIntent(context, tempFile)
        Result.success(SessionExportResult(displayPath, shareIntent))
    }
}
