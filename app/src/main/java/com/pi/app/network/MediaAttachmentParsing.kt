package com.pi.app.network

import com.google.gson.JsonElement
import com.pi.app.data.model.MediaAttachment
import com.pi.app.data.model.MediaKind

/**
 * SSE 事件与 REST 响应共用的媒体附件解析。
 *
 * 这些逻辑原先在 PiApiClient 与 PiSseClient 中各存一份逐字节相同的副本，
 * 任何一侧的修复都不会同步到另一侧——收口到这里，只留一份真相。
 */
internal object MediaAttachmentParsing {

    /** 输出文本里内嵌的 `<svg>…</svg>`。原先两个文件各自内联编译这个正则。 */
    val SVG_BLOCK_REGEX = Regex("""<svg[\s\S]*?</svg>""", RegexOption.IGNORE_CASE)

    /** 视为「文件类」的工具名。提到常量，避免每次调用重建 set。 */
    private val FILE_TOOL_NAMES = setOf(
        "read", "view_file", "read_file", "write", "write_file", "open"
    )

    private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".pdf")

    /**
     * 从工具的输入参数或预览文本里解析出一个媒体附件（图片 / SVG / PDF）。
     * 无法确定类型时返回空列表。
     */
    fun fromToolArgs(toolName: String, preview: String?, argsElem: JsonElement?): List<MediaAttachment> {
        val rawPath = preview?.takeIf { it.isNotBlank() } ?: run {
            if (argsElem != null && argsElem.isJsonObject) {
                val obj = argsElem.asJsonObject
                (obj.get("path") ?: obj.get("file_path") ?: obj.get("filePath"))
                    ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
            } else {
                null
            }
        } ?: return emptyList()

        val cleanPath = rawPath.trim().removeSurrounding("\"").removeSurrounding("'")
        val lower = cleanPath.lowercase()
        val isFileTool = toolName.lowercase() in FILE_TOOL_NAMES
        if (!isFileTool && IMAGE_EXTENSIONS.none { lower.endsWith(it) }) {
            return emptyList()
        }

        val (kind, mime) = when {
            lower.endsWith(".png") -> MediaKind.IMAGE to "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> MediaKind.IMAGE to "image/jpeg"
            lower.endsWith(".webp") -> MediaKind.IMAGE to "image/webp"
            lower.endsWith(".gif") -> MediaKind.IMAGE to "image/gif"
            lower.endsWith(".bmp") -> MediaKind.IMAGE to "image/bmp"
            lower.endsWith(".svg") -> MediaKind.SVG to "image/svg+xml"
            lower.endsWith(".pdf") -> MediaKind.PDF to "application/pdf"
            else -> return emptyList()
        }

        return listOf(
            MediaAttachment(
                kind = kind,
                mimeType = mime,
                filePath = cleanPath,
                title = cleanPath.substringAfterLast('/').substringAfterLast('\\')
            )
        )
    }

    /** 输出文本里第一个内嵌 SVG，没有则返回 null。 */
    fun firstInlineSvg(outputText: String?): MediaAttachment? {
        if (outputText.isNullOrBlank()) return null
        if (!outputText.contains("<svg", ignoreCase = true)) return null
        if (!outputText.contains("</svg>", ignoreCase = true)) return null
        val match = SVG_BLOCK_REGEX.find(outputText) ?: return null
        return MediaAttachment(
            kind = MediaKind.SVG,
            mimeType = "image/svg+xml",
            svgOrCode = match.value,
            title = "SVG 矢量图"
        )
    }
}
