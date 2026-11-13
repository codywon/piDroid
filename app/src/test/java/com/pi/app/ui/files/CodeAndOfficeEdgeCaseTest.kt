package com.pi.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CodeAndOfficeEdgeCaseTest {

    @Test
    fun `tokenizeLine handles indented json with escaped quotes and primitive value types`() {
        val line = "  \"k\\\"ey\": true,"
        val tokens = CodeSyntaxHighlighter.tokenizeLine(line, "json")
        assertTrue(tokens.any { it.type == CodeTokenType.PROPERTY_KEY && it.text == "\"k\\\"ey\"" })
        assertTrue(tokens.any { it.type == CodeTokenType.KEYWORD && it.text == "true" })
    }

    @Test
    fun `tokenizeGeneral keeps escaped quotes inside string literal`() {
        val line = "val s = \"a\\\"b\" // tail"
        val tokens = CodeSyntaxHighlighter.tokenizeLine(line, "kotlin")
        assertTrue(tokens.any { it.type == CodeTokenType.STRING && it.text == "\"a\\\"b\"" })
        assertTrue(tokens.any { it.type == CodeTokenType.COMMENT && it.text == "// tail" })
    }

    @Test
    fun `xlsx inlineStr joins multiple t runs and handles greater-than in attributes`() {
        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <workbook><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>
        """.trimIndent()
        val sheetXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet>
              <sheetData>
                <row r="1">
                  <c r="A1" t="inlineStr" data-expr="x>0"><is><r><t>Hel</t></r><r><t>lo</t></r></is></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val bytes = buildZip(
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheetXml
        )

        val preview = OfficeXmlPreview.extract(bytes, "sample.xlsx") as OfficePreview.Sheet
        assertEquals("Sheet1", preview.sheetName)
        assertEquals(listOf(listOf("Hello")), preview.rows)
    }

    private fun buildZip(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
