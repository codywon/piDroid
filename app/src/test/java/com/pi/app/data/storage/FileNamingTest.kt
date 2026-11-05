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

    /**
     * 只由点组成的名字必须退回兜底名。
     *
     * `"."` / `".."` 不含任何非法字符，旧实现会原样放行 —— 而 Android 9 及以下的公共
     * 下载目录走的是 `File(targetDir, safeName)`，`".."` 会解析成**父目录**：
     * 写盘只能得到一句语焉不明的 IOException，返回给用户的保存路径里还会出现一个
     * 形似路径穿越的 `..`。这类名字没有任何合法用途，直接换掉。
     */
    @Test
    fun `dot only names fall back instead of escaping the directory`() {
        assertEquals("download", FileDownloader.sanitizeFileName(".."))
        assertEquals("download", FileDownloader.sanitizeFileName("."))
        assertEquals("download", FileDownloader.sanitizeFileName("..."))
        assertEquals("download", FileDownloader.sanitizeFileName("  ..  "))
        // 下划线是合法字符，会被 trim('_') 剥掉，剩下的 "_._" 同样等价于 "."
        assertEquals("download", FileDownloader.sanitizeFileName("_._"))
        assertEquals("我的文件", FileDownloader.sanitizeFileName("..", "我的文件"))
    }

    /** 但含点的正常文件名不能被误伤。 */
    @Test
    fun `names that merely contain dots are kept`() {
        assertEquals("v1.2.pdf", FileDownloader.sanitizeFileName("v1.2.pdf"))
        assertEquals("..hidden", FileDownloader.sanitizeFileName("..hidden"))
        assertEquals("a.b.c", FileDownloader.sanitizeFileName("a.b.c"))
    }
}
