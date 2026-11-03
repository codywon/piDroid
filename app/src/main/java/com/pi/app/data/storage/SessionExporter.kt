package com.pi.app.data.storage

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

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
}
