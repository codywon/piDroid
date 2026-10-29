package com.pi.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Git Bash / MSYS 形态路径（`/d/myscript/proj/docs/a.md`）的回归测试。
 *
 * 守护的线上故障：pi 在 Windows 上把文件路径写成 `/d/...`，App 原样拼进
 * `/api/files/<path>?type=read|download`，pi-web 的“允许根”检查判定它不在任何
 * 会话工作目录内，于是报告类 md 打开报 `403 Access denied`、
 * 「用其他应用打开」下载报 `403`（文件其实就在工作区里）。
 *
 * 只测纯 JVM 逻辑：这两个函数刻意不碰 android.net.Uri，才能在本机跑单元测试。
 */
class FileLinksMsysPathTest {

    @Test
    fun `msys drive path becomes windows drive path when cwd is windows`() {
        assertEquals(
            "D:/myscript/eldercare-agent-platform/docs/reviews/query-latency-local-acceptance-2026-10-29.md",
            FileLinks.normalizeMsysDrivePath(
                "/d/myscript/eldercare-agent-platform/docs/reviews/query-latency-local-acceptance-2026-10-29.md",
                "D:/myscript/eldercare-agent-platform"
            )
        )
    }

    @Test
    fun `drive letter comes from the link not from the cwd`() {
        assertEquals(
            "C:/Users/29092/.pi/agent/skills/x/SKILL.md",
            FileLinks.normalizeMsysDrivePath(
                "/c/Users/29092/.pi/agent/skills/x/SKILL.md",
                "D:/myscript/pi-app"
            )
        )
        assertEquals(
            "E:/data/a.md",
            FileLinks.normalizeMsysDrivePath("/E/data/a.md", "D:\\myscript\\pi-app")
        )
    }

    @Test
    fun `posix paths stay untouched on a posix gateway`() {
        // Linux 网关上 `/d/...` 是真路径，转换会把它写坏。
        assertEquals(
            "/home/me/proj/docs/a.md",
            FileLinks.normalizeMsysDrivePath("/home/me/proj/docs/a.md", "/home/me/proj")
        )
        assertEquals(
            "/d/proj/docs/a.md",
            FileLinks.normalizeMsysDrivePath("/d/proj/docs/a.md", "/home/me/proj")
        )
        assertEquals(
            "/d/proj/docs/a.md",
            FileLinks.normalizeMsysDrivePath("/d/proj/docs/a.md", null)
        )
    }

    @Test
    fun `non drive like absolute paths are left alone`() {
        // 首段不是单个盘符字母：`/docs/a.md`、`/dd/a.md`、`/data/x` 都不是 MSYS 盘符路径。
        assertEquals(
            "/docs/a.md",
            FileLinks.normalizeMsysDrivePath("/docs/a.md", "D:/myscript/pi-app")
        )
        assertEquals(
            "/dd/a.md",
            FileLinks.normalizeMsysDrivePath("/dd/a.md", "D:/myscript/pi-app")
        )
        assertEquals(
            "D:/myscript/pi-app/a.md",
            FileLinks.normalizeMsysDrivePath("D:/myscript/pi-app/a.md", "D:/myscript/pi-app")
        )
        assertEquals("", FileLinks.normalizeMsysDrivePath("", "D:/myscript/pi-app"))
    }

    @Test
    fun `windows base dir detection`() {
        assertTrue(FileLinks.isWindowsBaseDir("D:/myscript/pi-app"))
        assertTrue(FileLinks.isWindowsBaseDir("D:\\myscript\\pi-app"))
        assertTrue(FileLinks.isWindowsBaseDir("//server/share/proj"))
        assertFalse(FileLinks.isWindowsBaseDir("/home/me/proj"))
        assertFalse(FileLinks.isWindowsBaseDir(""))
        assertFalse(FileLinks.isWindowsBaseDir(null))
    }
}
