package com.pi.app.ui.files

import com.pi.app.data.model.FileMeta
import com.pi.app.data.model.FileTextChunk
import com.pi.app.data.model.formatBytes
import com.pi.app.ui.chat.ArtifactKind
import java.io.File
import java.util.Locale

private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkdn", "mkd", "mdx")
private val IMAGE_FILE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
private val AUDIO_FILE_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "flac", "opus", "amr")
private val VIDEO_FILE_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi")
private val OFFICE_EXTERNAL_EXTENSIONS = setOf("doc", "wps", "xls", "xlsx", "et", "ppt", "pptx", "dps")
private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "apk")

/**
 * 判定预览类型：mime 优先，扩展名兜底（meta 拿不到时仍然能分派）。
 * 纯 Kotlin 逻辑，不依赖 Android 运行时。
 */
internal fun artifactKindOf(absPath: String, mime: String = ""): ArtifactKind {
    val clean = absPath.replace('\\', '/').trimEnd('/')
    val filename = if (clean.contains('/')) clean.substringAfterLast('/') else clean
    val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val lowerMime = mime.lowercase(Locale.ROOT)
    return when {
        // SVG 是矢量图，必须交给渲染器而不是 BitmapFactory
        ext == "svg" || lowerMime.contains("svg") -> ArtifactKind.SVG
        lowerMime.startsWith("image/") || ext in IMAGE_FILE_EXTENSIONS -> ArtifactKind.IMAGE
        ext in MARKDOWN_EXTENSIONS -> ArtifactKind.MARKDOWN
        lowerMime == "application/pdf" || ext == "pdf" -> ArtifactKind.PDF
        lowerMime.contains("wordprocessingml") || ext == "docx" -> ArtifactKind.DOCX
        ext in OFFICE_EXTERNAL_EXTENSIONS || lowerMime.contains("spreadsheet") || lowerMime.contains("presentation") || lowerMime.contains("msword") -> ArtifactKind.OFFICE
        ext in ARCHIVE_EXTENSIONS || lowerMime.contains("zip") || lowerMime.contains("compressed") -> ArtifactKind.OFFICE
        lowerMime.startsWith("audio/") || ext in AUDIO_FILE_EXTENSIONS -> ArtifactKind.AUDIO
        lowerMime.startsWith("video/") || ext in VIDEO_FILE_EXTENSIONS -> ArtifactKind.VIDEO
        else -> ArtifactKind.TEXT
    }
}

/**
 * 远端文件数据源回调接口。
 */
internal interface FilePreviewDataSource {
    suspend fun getFileMeta(path: String): Result<FileMeta>
    suspend fun getFileTextChunk(path: String, offset: Long): Result<FileTextChunk>
    suspend fun getFilePreviewHtml(path: String): Result<String>
    suspend fun getFileBytes(path: String): Result<ByteArray>
    suspend fun downloadFileToCache(path: String): Result<File>

    /**
     * 按上限取原始字节（xlsx / pptx 端内解包预览用）。
     * 默认实现转发到 [getFileBytes]，保持已有实现（含单测里的假数据源）无需修改。
     */
    suspend fun getFileBytesLimited(path: String, maxBytes: Long): Result<ByteArray> = getFileBytes(path)
}

/**
 * 统一的文件预览加载结果。
 */
internal data class RemoteFilePreviewData(
    val path: String,
    val title: String,
    val kind: ArtifactKind,
    val mime: String,
    val metaLabel: String?,
    val text: String? = null,
    val imageBytes: ByteArray? = null,
    val pdfFile: File? = null,
    val docxHtml: String? = null,
    val officePreview: OfficePreview? = null,
    val message: String? = null,
    val nextOffset: Long = 0L,
    val sizeBytes: Long = 0L,
    val truncated: Boolean = false,
    val isBinary: Boolean = false
)

internal object RemoteFilePreviewLoader {

    /** xlsx / pptx 端内解包预览的大小上限（超过就走外部应用，避免占内存） */
    private const val OFFICE_INLINE_MAX_BYTES = 12L * 1024 * 1024

    /** 图片、PDF 这类格式不带「语言」语义 */
    private fun resolvedKindHint(absPath: String): Boolean {
        val ext = absPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext !in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "svg", "pdf")
    }

    private fun officeFallbackMessage(ext: String, sizeBytes: Long, error: String?): String {
        val desc = when (ext) {
            "xls", "xlsx", "et" -> "Excel 工作表"
            "ppt", "pptx", "dps" -> "PowerPoint 演示文稿"
            "doc", "wps" -> "Word 文档"
            "zip", "rar", "7z", "tar", "gz", "apk" -> "压缩包 / 安装包"
            else -> "办公 / 二进制文档"
        }
        val reason = if (error.isNullOrBlank()) "" else "（$error）"
        return "此文件为 $desc（${formatBytes(sizeBytes)}）$reason，推荐使用下方按钮调用系统应用（WPS / Office）打开查阅。"
    }

    /**
     * 加载远端文件预览所需数据并完成类型分派。
     */
    suspend fun load(
        absPath: String,
        dataSource: FilePreviewDataSource
    ): RemoteFilePreviewData {
        val clean = absPath.replace('\\', '/').trimEnd('/')
        val title = if (clean.contains('/')) clean.substringAfterLast('/') else clean

        val metaResult = dataSource.getFileMeta(absPath)
        val meta = metaResult.getOrNull()
        val metaMime = meta?.mime.orEmpty()
        val kind = artifactKindOf(absPath, metaMime)

        val metaLabel = meta?.let { m ->
            // 代码/文本类才带 language；图片、PDF 的 language 恒为 "text"，写出来只会误导
            val showLanguage = resolvedKindHint(absPath)
            listOfNotNull(
                m.sizeLabel.takeIf { it.isNotBlank() },
                m.language.takeIf { it.isNotBlank() && showLanguage },
                m.mime.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
        }

        var resolvedKind = kind
        var text: String? = null
        var imageBytes: ByteArray? = null
        var pdfFile: File? = null
        var docxHtml: String? = null
        var officePreview: OfficePreview? = null
        var message: String? = null
        var nextOffset = 0L
        var sizeBytes = meta?.size ?: 0L
        var truncated = false
        var isBinary = false
        val ext = title.substringAfterLast('.', "").lowercase(Locale.ROOT)

        when (resolvedKind) {
            ArtifactKind.IMAGE -> {
                val bytesRes = dataSource.getFileBytes(absPath)
                if (bytesRes.isSuccess) {
                    imageBytes = bytesRes.getOrNull()
                } else {
                    message = "读取图片失败：${bytesRes.exceptionOrNull()?.message}"
                }
            }
            ArtifactKind.DOCX -> {
                // pi-web 的 docx 预览上限 10MB（DOCX_PREVIEW_MAX_BYTES），超了服务端会拒
                if (meta != null && meta.size > 10L * 1024 * 1024) {
                    resolvedKind = ArtifactKind.OFFICE
                    isBinary = true
                    message = "Word 预览上限 10MB，推荐使用下方按钮调用系统应用（WPS / Office）打开。"
                } else {
                    val htmlRes = dataSource.getFilePreviewHtml(absPath)
                    if (htmlRes.isSuccess) {
                        docxHtml = htmlRes.getOrNull()
                    } else {
                        resolvedKind = ArtifactKind.OFFICE
                        isBinary = true
                        message = "Word 预览转换不可用，推荐使用下方按钮调用系统应用（WPS / Office）打开。"
                    }
                }
            }
            ArtifactKind.PDF -> {
                val pdfRes = dataSource.downloadFileToCache(absPath)
                if (pdfRes.isSuccess) {
                    pdfFile = pdfRes.getOrNull()
                } else {
                    message = "PDF 加载失败：${pdfRes.exceptionOrNull()?.message}"
                }
            }
            ArtifactKind.OFFICE -> {
                isBinary = true
                // xlsx / pptx 本质是 zip + XML：本机解包就能给出真预览，不必退回外部应用
                if (OfficeXmlPreview.isSupportedExtension(ext) && sizeBytes in 0..OFFICE_INLINE_MAX_BYTES) {
                    val bytesRes = dataSource.getFileBytesLimited(absPath, OFFICE_INLINE_MAX_BYTES)
                    val parsed = bytesRes.getOrNull()?.let { bytes ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            OfficeXmlPreview.extract(bytes, title)
                        }
                    }
                    if (parsed != null) {
                        officePreview = parsed
                        isBinary = false
                    } else {
                        message = officeFallbackMessage(ext, sizeBytes, bytesRes.exceptionOrNull()?.message)
                    }
                } else {
                    message = officeFallbackMessage(ext, sizeBytes, null)
                }
            }
            ArtifactKind.AUDIO, ArtifactKind.VIDEO -> {
                // 音视频无需预读正文，直接由播放器流式播放
            }
            else -> {
                val chunkRes = dataSource.getFileTextChunk(absPath, 0L)
                if (chunkRes.isSuccess) {
                    val chunk = chunkRes.getOrNull()!!
                    if (chunk.binary) {
                        resolvedKind = ArtifactKind.OFFICE
                        isBinary = true
                        message = "此文件为二进制文件（${formatBytes(chunk.size)}），不支持文本预览。推荐使用下方按钮调用外部工具打开。"
                    } else {
                        text = chunk.content.ifBlank { "（空文件）" }
                    }
                    nextOffset = chunk.nextOffset
                    truncated = chunk.truncated
                    sizeBytes = chunk.size
                } else {
                    text = "读取文件失败：${chunkRes.exceptionOrNull()?.message}"
                }
            }
        }

        return RemoteFilePreviewData(
            path = absPath,
            title = title,
            kind = resolvedKind,
            mime = metaMime,
            metaLabel = metaLabel,
            text = text,
            imageBytes = imageBytes,
            pdfFile = pdfFile,
            docxHtml = docxHtml,
            officePreview = officePreview,
            message = message,
            nextOffset = nextOffset,
            sizeBytes = sizeBytes,
            truncated = truncated,
            isBinary = isBinary
        )
    }
}
