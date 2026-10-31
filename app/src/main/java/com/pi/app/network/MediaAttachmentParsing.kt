package com.pi.app.network

import com.google.gson.JsonElement
import com.pi.app.data.model.MediaAttachment
import com.pi.app.data.model.MediaKind
import com.pi.app.data.model.MediaLoadPolicy

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

    private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".svg", ".pdf")

    private fun attachmentId(prefix: String?, suffix: String): String =
        if (prefix.isNullOrBlank()) {
            java.util.UUID.randomUUID().toString()
        } else {
            prefix + "#" + suffix
        }

    /**
     * 从工具的输入参数或预览文本里解析出一个媒体附件（图片 / SVG / PDF）。
     * 无法确定类型时返回空列表。
     *
     * @param idPrefix 形如 `tcid:<toolCallId>`，用于生成稳定 id（参见 [MediaLoadPolicy.TOOL_ATTACHMENT_ID_PREFIX]）。
     */
    fun fromToolArgs(
        toolName: String,
        preview: String?,
        argsElem: JsonElement?,
        idPrefix: String? = null
    ): List<MediaAttachment> {
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
                id = attachmentId(idPrefix, "arg"),
                kind = kind,
                mimeType = mime,
                filePath = cleanPath,
                title = cleanPath.substringAfterLast('/').substringAfterLast('\\')
            )
        )
    }

    /**
     * 从一条消息的 `content` 数组里解析图片 / PDF 附件。
     *
     * 关键点：pi-web 带 `deferMedia=1` 时会把工具结果图**换成 URL**，形如
     * `{type:"image", source:{type:"url", media_type:"image/png",
     *   url:"/api/sessions/{id}/entries/{eid}/tool-result-image?blockIndex=n"}}`。
     * 旧实现只读顶层 `url` / `image_url.url`，看不到 `source.url`，于是这类附件被整体丢弃，
     * 图片只能靠 `read` 工具入参里的文件路径兜底（bash 产出的图则永远不显示）。
     *
     * @param idPrefix 形如 `tcid:<toolCallId>`，用于生成稳定 id（参见 [MediaLoadPolicy.TOOL_ATTACHMENT_ID_PREFIX]）。
     */
    fun fromContent(
        element: JsonElement?,
        outputText: String? = null,
        idPrefix: String? = null
    ): List<MediaAttachment> {
        val list = mutableListOf<MediaAttachment>()
        if (element != null && element.isJsonArray) {
            var index = 0
            for (item in element.asJsonArray) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject
                val type = obj.get("type")?.takeIf { !it.isJsonNull }?.asString ?: ""
                if (type != "image" && type != "image_url" && type != "document" &&
                    !obj.has("data") && !obj.has("source")
                ) {
                    continue
                }
                val srcObj = obj.get("source")?.takeIf { it.isJsonObject }?.asJsonObject
                val mime = obj.get("mimeType")?.takeIf { !it.isJsonNull }?.asString
                    ?: obj.get("mediaType")?.takeIf { !it.isJsonNull }?.asString
                    ?: srcObj?.get("media_type")?.takeIf { !it.isJsonNull }?.asString
                    ?: srcObj?.get("mimeType")?.takeIf { !it.isJsonNull }?.asString
                    ?: "image/png"
                val b64 = obj.get("data")?.takeIf { !it.isJsonNull }?.asString
                    ?: srcObj?.get("data")?.takeIf { !it.isJsonNull }?.asString
                val urlVal = obj.get("url")?.takeIf { !it.isJsonNull }?.asString
                    ?: obj.get("image_url")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("url")?.takeIf { !it.isJsonNull }?.asString
                    ?: srcObj?.get("url")?.takeIf { !it.isJsonNull }?.asString
                val kind = when {
                    mime.contains("pdf", ignoreCase = true) -> MediaKind.PDF
                    mime.contains("svg", ignoreCase = true) -> MediaKind.SVG
                    else -> MediaKind.IMAGE
                }
                if (!b64.isNullOrBlank() || !urlVal.isNullOrBlank()) {
                    list.add(
                        MediaAttachment(
                            id = attachmentId(idPrefix, "img$index"),
                            kind = kind,
                            mimeType = mime,
                            dataBase64 = b64?.substringAfter("base64,", b64),
                            url = urlVal
                        )
                    )
                }
                index++
            }
        }
        firstInlineSvg(outputText)?.let { list.add(it) }
        return list
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
