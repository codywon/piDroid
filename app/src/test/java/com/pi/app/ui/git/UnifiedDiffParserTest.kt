package com.pi.app.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDiffParserTest {

    @Test
    fun `parses quoted git paths with spaces and octal utf8 escapes`() {
        val patch = buildString {
            appendLine("diff --git \"a/dir name/\\344\\270\\255\\346\\226\\207.txt\" \"b/dir name/\\344\\270\\255\\346\\226\\207.txt\"")
            appendLine("--- \"a/dir name/\\344\\270\\255\\346\\226\\207.txt\"")
            appendLine("+++ \"b/dir name/\\344\\270\\255\\346\\226\\207.txt\"")
            appendLine("@@ -1,1 +1,1 @@")
            appendLine("-hello")
            appendLine("+world")
        }

        val files = UnifiedDiffParser.parse(patch)
        assertEquals(1, files.size)
        assertEquals("dir name/中文.txt", files[0].oldPath)
        assertEquals("dir name/中文.txt", files[0].newPath)
    }

    @Test
    fun `word diff highlight never splits surrogate pairs in old or new text`() {
        val oldLine = "val s = \"😀A\""
        val newLine = "val s = \"😀B\""
        val patch = buildString {
            appendLine("diff --git a/Test.kt b/Test.kt")
            appendLine("--- a/Test.kt")
            appendLine("+++ b/Test.kt")
            appendLine("@@ -1,1 +1,1 @@")
            appendLine("-$oldLine")
            appendLine("+$newLine")
        }

        val files = UnifiedDiffParser.parse(patch)
        val removed = files[0].lines.first { it.type == PatchLineType.REMOVED }
        val added = files[0].lines.first { it.type == PatchLineType.ADDED }

        val remRange = removed.highlightRange
        val addRange = added.highlightRange
        assertNotNull(remRange)
        assertNotNull(addRange)

        // 高亮子串的首尾不应落在低/高代理字符中间
        val remSub = removed.text.substring(remRange!!.first, remRange.last + 1)
        val addSub = added.text.substring(addRange!!.first, addRange.last + 1)
        assertEquals("A", remSub)
        assertEquals("B", addSub)
        assertTrue(!Character.isLowSurrogate(removed.text[remRange.first]))
        assertTrue(!Character.isLowSurrogate(added.text[addRange.first]))
    }
}
