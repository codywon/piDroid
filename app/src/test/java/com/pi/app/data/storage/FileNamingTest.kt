package com.pi.app.data.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 针对 [FileDownloader.sanitizeFileName] 命名过滤规则的纯 JVM 单元测试。
 */
class FileNamingTest {

    @Test
    fun `sanitizeFileName cleans illegal filesystem characters`() {
        assertEquals("report_2026.pdf", FileDownloader.sanitizeFileName("report/2026.pdf"))
        assertEquals("data_test_1.json", FileDownloader.sanitizeFileName("data:test*1.json"))
        assertEquals("my_notes_.txt", FileDownloader.sanitizeFileName("my?notes|.txt"))
        assertEquals("normal.png", FileDownloader.sanitizeFileName("normal.png"))
        assertEquals("download", FileDownloader.sanitizeFileName(""))
        assertEquals("download", FileDownloader.sanitizeFileName("   "))
        assertEquals("download", FileDownloader.sanitizeFileName("///:::***"))
    }
}
