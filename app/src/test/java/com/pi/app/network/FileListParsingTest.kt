package com.pi.app.network

import com.pi.app.data.model.FileEntry
import com.pi.app.data.model.FileMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 针对 [FileListParsing] 与相关数据模型的纯 JVM 单元测试。
 * 遵循项目规范：不依赖 Android 运行时 (android.* / Context / Uri)。
 */
class FileListParsingTest {

    @Test
    fun `parseListing parses full directory payload with mixed entries and links`() {
        val json = """
            {
              "path": "/workspace/demo",
              "entries": [
                {
                  "name": "README.md",
                  "isDir": false,
                  "size": 1024,
                  "modified": "2026-11-01T12:00:00Z"
                },
                {
                  "name": "src",
                  "isDir": true,
                  "size": 0,
                  "modified": ""
                },
                {
                  "name": "node_modules",
                  "isDir": true,
                  "size": 0,
                  "modified": "",
                  "hidden": "ignored"
                },
                {
                  "name": "shared_lib",
                  "isDir": true,
                  "size": 0,
                  "modified": "",
                  "outsideLinkTarget": "/outside/shared",
                  "outsideLinkEncloses": true
                }
              ]
            }
        """.trimIndent()

        val listing = FileListParsing.parseListing(json)
        assertNotNull("Listing should parse successfully", listing)
        assertEquals("/workspace/demo", listing!!.path)
        assertEquals(4, listing.entries.size)

        // 验证目录在前，文件在后
        val first = listing.entries[0]
        assertTrue("First entry must be a directory", first.isDir)

        val linkEntry = listing.entries.firstOrNull { it.name == "shared_lib" }
        assertNotNull(linkEntry)
        assertTrue(linkEntry!!.isDir)
        assertTrue(linkEntry.isOutsideLink)
        assertEquals("/outside/shared", linkEntry.outsideLinkTarget)
        assertTrue(linkEntry.outsideLinkEncloses)

        val hiddenEntry = listing.entries.firstOrNull { it.name == "node_modules" }
        assertNotNull(hiddenEntry)
        assertEquals("ignored", hiddenEntry!!.hidden)

        val fileEntry = listing.entries.firstOrNull { it.name == "README.md" }
        assertNotNull(fileEntry)
        assertFalse(fileEntry!!.isDir)
        assertEquals(1024L, fileEntry.size)
    }

    @Test
    fun `parseListing handles empty directory gracefully`() {
        val json = """{"path": "/empty/dir", "entries": []}"""
        val listing = FileListParsing.parseListing(json)
        assertNotNull(listing)
        assertEquals("/empty/dir", listing!!.path)
        assertTrue(listing.entries.isEmpty())
    }

    @Test
    fun `parseListing returns null on error payload and parseError extracts message`() {
        val json = """{"error": "Access denied", "status": 403}"""
        val listing = FileListParsing.parseListing(json)
        assertNull("Error payload must yield null listing", listing)

        val errorMsg = FileListParsing.parseError(json)
        assertEquals("Access denied", errorMsg)
    }

    @Test
    fun `parseIndexMatches correctly extracts matches list`() {
        val json = """
            {
              "matches": [
                { "path": "src/App.tsx", "isDir": false },
                { "path": "docs", "isDir": true }
              ]
            }
        """.trimIndent()

        val matches = FileListParsing.parseIndexMatches(json)
        assertEquals(2, matches.size)
        assertEquals("src/App.tsx", matches[0].path)
        assertFalse(matches[0].isDir)
        assertEquals("docs", matches[1].path)
        assertTrue(matches[1].isDir)
    }

    @Test
    fun `joinPath combines paths and rejects parent traversal`() {
        assertEquals("src/components/App.tsx", FileListParsing.joinPath("src", "components/App.tsx"))
        assertEquals("src/components/App.tsx", FileListParsing.joinPath("src/", "/components/App.tsx"))
        assertEquals("D:/project/src", FileListParsing.joinPath("D:/project", "src"))
        assertEquals("C:/file.txt", FileListParsing.joinPath("C:", "file.txt"))

        try {
            FileListParsing.joinPath("/safe/root", "../escape.txt")
            fail("Must throw IllegalArgumentException for .. traversal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(".."))
        }

        try {
            FileListParsing.joinPath("/safe/root", "sub/../../escape.txt")
            fail("Must throw IllegalArgumentException for .. traversal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(".."))
        }
    }

    @Test
    fun `parentPath resolves parents across POSIX, Windows and UNC`() {
        // POSIX
        assertEquals("/a/b", FileListParsing.parentPath("/a/b/c"))
        assertEquals("/a", FileListParsing.parentPath("/a/b"))
        assertEquals("/", FileListParsing.parentPath("/a"))
        assertNull(FileListParsing.parentPath("/"))

        // Windows Drive
        assertEquals("D:/work/project", FileListParsing.parentPath("D:/work/project/file.txt"))
        assertEquals("D:/work", FileListParsing.parentPath("D:/work/project"))
        assertEquals("D:/", FileListParsing.parentPath("D:/work"))
        assertNull(FileListParsing.parentPath("D:/"))
        assertNull(FileListParsing.parentPath("D:"))

        // UNC
        assertEquals("//host/share/sub", FileListParsing.parentPath("//host/share/sub/deep"))
        assertEquals("//host/share", FileListParsing.parentPath("//host/share/sub"))
        assertNull(FileListParsing.parentPath("//host/share"))
    }

    @Test
    fun `displayName extracts clean terminal segment`() {
        assertEquals("README.md", FileListParsing.displayName("/workspace/README.md"))
        assertEquals("sub", FileListParsing.displayName("D:/workspace/sub/"))
        assertEquals("file.txt", FileListParsing.displayName("file.txt"))
        assertEquals("", FileListParsing.displayName(""))
    }

    @Test
    fun `relativePath strips cwd accurately`() {
        // POSIX
        assertEquals("src/main.rs", FileListParsing.relativePath("/home/user/repo/src/main.rs", "/home/user/repo"))
        assertEquals("src/main.rs", FileListParsing.relativePath("/home/user/repo/src/main.rs", "/home/user/repo/"))
        assertEquals("", FileListParsing.relativePath("/home/user/repo", "/home/user/repo"))

        // Windows Case Insensitive
        assertEquals("src/App.tsx", FileListParsing.relativePath("D:/Project/src/App.tsx", "d:/project"))

        // Outside cwd
        assertEquals("/other/path/file.txt", FileListParsing.relativePath("/other/path/file.txt", "/home/user/repo"))
    }

    @Test
    fun `sortEntries keeps directories before files and sorts case-insensitively`() {
        val entries = listOf(
            FileEntry(name = "zebra.txt", isDir = false),
            FileEntry(name = "Alpha", isDir = true),
            FileEntry(name = "apple.txt", isDir = false),
            FileEntry(name = "beta", isDir = true)
        )

        val sorted = FileListParsing.sortEntries(entries)
        assertEquals("Alpha", sorted[0].name)
        assertTrue(sorted[0].isDir)
        assertEquals("beta", sorted[1].name)
        assertTrue(sorted[1].isDir)
        assertEquals("apple.txt", sorted[2].name)
        assertFalse(sorted[2].isDir)
        assertEquals("zebra.txt", sorted[3].name)
        assertFalse(sorted[3].isDir)
    }

    @Test
    fun `FileMeta version derives size and mtime pair`() {
        val metaWithTime = FileMeta(size = 2048L, mtime = "2026-11-01T10:00:00.000Z")
        assertEquals("2048:2026-11-01T10:00:00.000Z", metaWithTime.version)

        val metaWithoutTime = FileMeta(size = 2048L, mtime = null)
        assertNull(metaWithoutTime.version)
    }
}
