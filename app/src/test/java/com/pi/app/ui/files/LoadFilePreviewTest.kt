package com.pi.app.ui.files

import com.pi.app.data.model.FileMeta
import com.pi.app.data.model.FileTextChunk
import com.pi.app.ui.chat.ArtifactKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 针对 [RemoteFilePreviewLoader] 与 [artifactKindOf] 预览分派逻辑的纯 JVM 单元测试。
 */
class LoadFilePreviewTest {

    @Test
    fun `artifactKindOf classifies files accurately by extension and mime`() {
        assertEquals(ArtifactKind.IMAGE, artifactKindOf("photo.png", "image/png"))
        assertEquals(ArtifactKind.IMAGE, artifactKindOf("photo.JPG", ""))
        assertEquals(ArtifactKind.SVG, artifactKindOf("icon.svg", ""))
        assertEquals(ArtifactKind.SVG, artifactKindOf("diagram", "image/svg+xml"))
        assertEquals(ArtifactKind.MARKDOWN, artifactKindOf("README.md", "text/markdown"))
        assertEquals(ArtifactKind.MARKDOWN, artifactKindOf("NOTES.markdown", ""))
        assertEquals(ArtifactKind.PDF, artifactKindOf("doc.pdf", "application/pdf"))
        assertEquals(ArtifactKind.DOCX, artifactKindOf("report.docx", ""))
        assertEquals(ArtifactKind.OFFICE, artifactKindOf("table.xlsx", ""))
        assertEquals(ArtifactKind.OFFICE, artifactKindOf("slide.pptx", ""))
        assertEquals(ArtifactKind.OFFICE, artifactKindOf("archive.zip", ""))
        assertEquals(ArtifactKind.AUDIO, artifactKindOf("sound.mp3", "audio/mpeg"))
        assertEquals(ArtifactKind.VIDEO, artifactKindOf("video.mp4", "video/mp4"))
        assertEquals(ArtifactKind.TEXT, artifactKindOf("build.gradle.kts", "text/plain"))
        assertEquals(ArtifactKind.TEXT, artifactKindOf("unknown.xyz", ""))
    }

    private class FakeDataSource(
        var meta: FileMeta? = null,
        var textChunk: FileTextChunk? = null,
        var html: String? = null,
        var bytes: ByteArray? = null,
        var downloadedFile: File? = null,
        var htmlCallCount: Int = 0,
        var bytesCallCount: Int = 0,
        var textChunkCallCount: Int = 0,
        var downloadCallCount: Int = 0
    ) : FilePreviewDataSource {
        override suspend fun getFileMeta(path: String): Result<FileMeta> {
            return meta?.let { Result.success(it) } ?: Result.failure(Exception("Meta not found"))
        }

        override suspend fun getFileTextChunk(path: String, offset: Long): Result<FileTextChunk> {
            textChunkCallCount++
            return textChunk?.let { Result.success(it) } ?: Result.failure(Exception("Chunk read error"))
        }

        override suspend fun getFilePreviewHtml(path: String): Result<String> {
            htmlCallCount++
            return html?.let { Result.success(it) } ?: Result.failure(Exception("HTML preview error"))
        }

        override suspend fun getFileBytes(path: String): Result<ByteArray> {
            bytesCallCount++
            return bytes?.let { Result.success(it) } ?: Result.failure(Exception("Bytes read error"))
        }

        override suspend fun downloadFileToCache(path: String): Result<File> {
            downloadCallCount++
            return downloadedFile?.let { Result.success(it) } ?: Result.failure(Exception("Download failed"))
        }
    }

    @Test
    fun `load image dispatches to getFileBytes`() = runBlocking {
        val fakeBytes = byteArrayOf(1, 2, 3)
        val ds = FakeDataSource(
            meta = FileMeta(size = 3L, mime = "image/png"),
            bytes = fakeBytes
        )

        val result = RemoteFilePreviewLoader.load("/path/to/image.png", ds)
        assertEquals(ArtifactKind.IMAGE, result.kind)
        assertEquals("image.png", result.title)
        assertEquals(1, ds.bytesCallCount)
        assertNotNull(result.imageBytes)
        assertEquals(3, result.imageBytes!!.size)
        assertNull(result.message)
    }

    @Test
    fun `load docx under 10MB fetches html preview`() = runBlocking {
        val ds = FakeDataSource(
            meta = FileMeta(size = 5L * 1024 * 1024, mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            html = "<html><body>Doc</body></html>"
        )

        val result = RemoteFilePreviewLoader.load("report.docx", ds)
        assertEquals(ArtifactKind.DOCX, result.kind)
        assertEquals(1, ds.htmlCallCount)
        assertEquals("<html><body>Doc</body></html>", result.docxHtml)
        assertNull(result.message)
    }

    @Test
    fun `load docx exceeding 10MB skips preview call and sets message`() = runBlocking {
        val ds = FakeDataSource(
            meta = FileMeta(size = 11L * 1024 * 1024, mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            html = "should not be called"
        )

        val result = RemoteFilePreviewLoader.load("huge.docx", ds)
        assertEquals(ArtifactKind.OFFICE, result.kind)
        assertEquals(0, ds.htmlCallCount)
        assertNull(result.docxHtml)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("10MB"))
    }

    @Test
    fun `load excel sheet classifies as office kind and provides external app guidance`() = runBlocking {
        val ds = FakeDataSource(
            meta = FileMeta(size = 50000L, mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        )

        val result = RemoteFilePreviewLoader.load("budget.xlsx", ds)
        assertEquals(ArtifactKind.OFFICE, result.kind)
        assertTrue(result.isBinary)
        assertEquals(0, ds.textChunkCallCount)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("Excel 工作表"))
    }

    @Test
    fun `load pdf downloads file to cache for local rendering`() = runBlocking {
        val temp = File.createTempFile("test_pdf_", ".pdf")
        try {
            val ds = FakeDataSource(
                meta = FileMeta(size = 2048L, mime = "application/pdf"),
                downloadedFile = temp
            )

            val result = RemoteFilePreviewLoader.load("manual.pdf", ds)
            assertEquals(ArtifactKind.PDF, result.kind)
            assertEquals(1, ds.downloadCallCount)
            assertEquals(temp, result.pdfFile)
            assertNull(result.message)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `load text chunk sets content and pagination flags`() = runBlocking {
        val ds = FakeDataSource(
            meta = FileMeta(size = 1000L, mime = "text/plain"),
            textChunk = FileTextChunk(
                content = "Line 1\nLine 2",
                nextOffset = 256L,
                truncated = true,
                size = 1000L,
                binary = false
            )
        )

        val result = RemoteFilePreviewLoader.load("log.txt", ds)
        assertEquals(ArtifactKind.TEXT, result.kind)
        assertEquals("Line 1\nLine 2", result.text)
        assertEquals(256L, result.nextOffset)
        assertTrue(result.truncated)
        assertFalse(result.isBinary)
    }

    @Test
    fun `load archive zip sets office kind with compression description`() = runBlocking {
        val ds = FakeDataSource(
            meta = FileMeta(size = 5000L, mime = "application/zip")
        )

        val result = RemoteFilePreviewLoader.load("archive.zip", ds)
        assertEquals(ArtifactKind.OFFICE, result.kind)
        assertTrue(result.isBinary)
        assertNull(result.text)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("压缩包"))
    }

    @Test
    fun `load unknown binary file sets office kind with binary message`() = runBlocking {
        val ds = FakeDataSource(
            meta = FileMeta(size = 5000L, mime = "application/octet-stream"),
            textChunk = FileTextChunk(
                content = "",
                nextOffset = 0L,
                truncated = false,
                size = 5000L,
                binary = true
            )
        )

        val result = RemoteFilePreviewLoader.load("firmware.bin", ds)
        assertEquals(ArtifactKind.OFFICE, result.kind)
        assertTrue(result.isBinary)
        assertNull(result.text)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("二进制文件"))
    }

    @Test
    fun `audio and video do not perform upfront network reads`() = runBlocking {
        val ds = FakeDataSource(
            meta = FileMeta(size = 10000L, mime = "audio/mpeg")
        )

        val result = RemoteFilePreviewLoader.load("podcast.mp3", ds)
        assertEquals(ArtifactKind.AUDIO, result.kind)
        assertEquals(0, ds.bytesCallCount)
        assertEquals(0, ds.textChunkCallCount)
        assertEquals(0, ds.htmlCallCount)
        assertEquals(0, ds.downloadCallCount)
    }
}
