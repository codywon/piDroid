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

/**
 * 判定预览类型：mime 优先，扩展名兜底（meta 拿不到时仍然能分派）。
 * 纯 Kotlin 逻辑，不依赖 Android 运行时。
 */
fun artifactKindOf(absPath: String, mime: String = ""): ArtifactKind {
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
        lowerMime.startsWith("audio/") || ext in AUDIO_FILE_EXTENSIONS -> ArtifactKind.AUDIO
        lowerMime.startsWith("video/") || ext in VIDEO_FILE_EXTENSIONS -> ArtifactKind.VIDEO
        else -> ArtifactKind.TEXT
    }
}

/**
 * 远端文件数据源回调接口。
 */
interface FilePreviewDataSource {
    suspend fun getFileMeta(path: String): Result<FileMeta>
    suspend fun getFileTextChunk(path: String, offset: Long): Result<FileTextChunk>
    suspend fun getFilePreviewHtml(path: String): Result<String>
    suspend fun getFileBytes(path: String): Result<ByteArray>
    suspend fun downloadFileToCache(path: String): Result<File>
}

/**
 * 统一的文件预览加载结果。
 */
data class RemoteFilePreviewData(
    val path: String,
    val title: String,
    val kind: ArtifactKind,
    val mime: String,
    val metaLabel: String?,
    val text: String? = null,
    val imageBytes: ByteArray? = null,
    val pdfFile: File? = null,
    val docxHtml: String? = null,
    val message: String? = null,
    val nextOffset: Long = 0L,
    val sizeBytes: Long = 0L,
    val truncated: Boolean = false,
    val isBinary: Boolean = false
)

object RemoteFilePreviewLoader {

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
            listOfNotNull(
                m.sizeLabel.takeIf { it.isNotBlank() },
                m.language.takeIf { it.isNotBlank() },
                m.mime.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
        }

        var text: String? = null
        var imageBytes: ByteArray? = null
        var pdfFile: File? = null
        var docxHtml: String? = null
        var message: String? = null
        var nextOffset = 0L
        var sizeBytes = meta?.size ?: 0L
        var truncated = false
        var isBinary = false

        when (kind) {
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
                    message = "Word 预览上限 10MB，可用下方「用其他应用打开」交给 WPS"
                } else {
                    val htmlRes = dataSource.getFilePreviewHtml(absPath)
                    if (htmlRes.isSuccess) {
                        docxHtml = htmlRes.getOrNull()
                    } else {
                        message = "Word 预览失败：${htmlRes.exceptionOrNull()?.message}"
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
            ArtifactKind.AUDIO, ArtifactKind.VIDEO -> {
                // 音视频无需预读正文，直接由播放器流式播放
            }
            else -> {
                val chunkRes = dataSource.getFileTextChunk(absPath, 0L)
                if (chunkRes.isSuccess) {
                    val chunk = chunkRes.getOrNull()!!
                    if (chunk.binary) {
                        isBinary = true
                        message = "此文件为二进制文件（${formatBytes(chunk.size)}），不支持文本预览。可点击下方「用其他应用打开」调用外部工具查看。"
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
            kind = kind,
            mime = metaMime,
            metaLabel = metaLabel,
            text = text,
            imageBytes = imageBytes,
            pdfFile = pdfFile,
            docxHtml = docxHtml,
            message = message,
            nextOffset = nextOffset,
            sizeBytes = sizeBytes,
            truncated = truncated,
            isBinary = isBinary
        )
    }
}
