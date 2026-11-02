package com.pi.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 针对 [OfficeXmlPreview]（xlsx / pptx 端内解包预览）的纯 JVM 单元测试。
 *
 * 用 `java.util.zip` 现场造一个最小 OOXML 包，不依赖 Android 运行时，
 * 也不依赖任何真实的 Office 文件。
 */
class OfficeXmlPreviewTest {

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun minimalXlsx(): ByteArray = zipOf(
        mapOf(
            "xl/workbook.xml" to
                """<?xml version="1.0"?><workbook><sheets><sheet name="预算表" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/sharedStrings.xml" to
                """<?xml version="1.0"?><sst count="4">
                   <si><t>项目</t></si>
                   <si><t>金额</t></si>
                   <si><t>服务器 &amp; 存储</t></si>
                   <si><r><t>富</t></r><r><t>文本</t></r></si>
                </sst>""",
            "xl/worksheets/sheet1.xml" to
                """<?xml version="1.0"?><worksheet><sheetData>
                   <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                   <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>1234.5</v></c></row>
                   <row r="3"><c r="A3" t="inlineStr"><is><t>备注</t></is></c><c r="C3" t="s"><v>3</v></c></row>
                   <row r="4"><c r="A4"><v></v></c></row>
                </sheetData></worksheet>"""
        )
    )

    private fun minimalPptx(): ByteArray = zipOf(
        mapOf(
            "ppt/slides/slide1.xml" to
                """<?xml version="1.0"?><p:sld><p:cSld><p:spTree>
                   <p:sp><p:txBody><a:p><a:r><a:t>季度汇报</a:t></a:r></a:p>
                   <a:p><a:r><a:t>营收增长 18%</a:t></a:r></a:p></p:txBody></p:sp>
                </p:spTree></p:cSld></p:sld>""",
            "ppt/slides/slide2.xml" to
                """<?xml version="1.0"?><p:sld><p:cSld><p:spTree>
                   <p:sp><p:txBody><a:p><a:r><a:t>下季度重点</a:t></a:r></a:p></p:txBody></p:sp>
                </p:spTree></p:cSld></p:sld>"""
        )
    )

    @Test
    fun `extract xlsx yields sheet name rows and shared strings`() {
        val preview = OfficeXmlPreview.extract(minimalXlsx(), "预算.xlsx")
        assertNotNull(preview)
        val sheet = preview as OfficePreview.Sheet
        assertEquals("预算表", sheet.sheetName)
        assertEquals(3, sheet.rows.size, "尾部全空行应被去掉")
        assertEquals(listOf("项目", "金额"), sheet.rows[0])
        assertEquals(listOf("服务器 & 存储", "1234.5"), sheet.rows[1])
        // 第三行：A 列在 inlineStr、C 列富文本，中间 B 列补空串
        assertEquals(listOf("备注", "", "富文本"), sheet.rows[2])
    }

    @Test
    fun `extract pptx yields slides in numeric order with text lines`() {
        val preview = OfficeXmlPreview.extract(minimalPptx(), "deck.pptx")
        assertNotNull(preview)
        val slides = (preview as OfficePreview.Slides).slides
        assertEquals(2, slides.size)
        assertEquals(1, slides[0].index)
        assertEquals(listOf("季度汇报", "营收增长 18%"), slides[0].lines)
        assertEquals(listOf("下季度重点"), slides[1].lines)
    }

    @Test
    fun `extract returns null for unsupported or broken payloads`() {
        assertNull(OfficeXmlPreview.extract(minimalXlsx(), "legacy.xls"))
        assertNull(OfficeXmlPreview.extract(minimalXlsx(), "archive.zip"))
        // 不是 zip 的字节：不能崩，只能返回 null（调用方回落外部打开卡片）
        assertNull(OfficeXmlPreview.extract("not a zip".toByteArray(), "broken.xlsx"))
        // 是 zip 但没有 worksheet
        assertNull(OfficeXmlPreview.extract(zipOf(mapOf("xl/workbook.xml" to "<workbook/>")), "empty.xlsx"))
    }

    @Test
    fun `isSupportedExtension only accepts xlsx and pptx`() {
        assertTrue(OfficeXmlPreview.isSupportedExtension("xlsx"))
        assertTrue(OfficeXmlPreview.isSupportedExtension("PPTX"))
        assertTrue(!OfficeXmlPreview.isSupportedExtension("docx"))
        assertTrue(!OfficeXmlPreview.isSupportedExtension("xls"))
        assertTrue(!OfficeXmlPreview.isSupportedExtension(""))
    }

    @Test
    fun `unescapeXml handles entities and numeric code points`() {
        assertEquals("a & b < c > d \" e ' f", OfficeXmlPreview.unescapeXml("a &amp; b &lt; c &gt; d &quot; e &apos; f"))
        assertEquals("π", OfficeXmlPreview.unescapeXml("&#960;"))
        assertEquals("π", OfficeXmlPreview.unescapeXml("&#x3C0;"))
        // 未知实体原样保留，不做破坏性替换
        assertEquals("&unknown;", OfficeXmlPreview.unescapeXml("&unknown;"))
    }
}
