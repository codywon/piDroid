package com.pi.app.ui.files

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 端内 Office（OOXML）预览数据。
 *
 * pi-web 只有 `.docx` 走服务端 mammoth 转 HTML，`.xlsx` / `.pptx` 在 pi-web 里
 * 也只能「下载 / 外部打开」。手机端把这两类补上：xlsx / pptx 本质都是 zip 包
 * + XML，直接在本机解包读数据即可，不需要任何服务端改动（网关是纯反代，
 * 也不允许自造接口）。
 *
 * 纯 JVM 实现（只用 `java.util.zip` 与字符串扫描），可直接跑单元测试。
 */
internal sealed interface OfficePreview {
    /** xlsx 的一个工作表 */
    data class Sheet(
        val sheetName: String,
        val rows: List<List<String>>,
        val truncated: Boolean
    ) : OfficePreview

    /** pptx 的一页幻灯片（按顺序抽出可读文本行） */
    data class Slides(
        val slides: List<Slide>
    ) : OfficePreview

    data class Slide(val index: Int, val lines: List<String>)
}

internal object OfficeXmlPreview {
    /** 表格最多渲染的行列数：手机屏再多的列也看不了，超出部分只提示被截断 */
    const val MAX_ROWS = 200
    const val MAX_COLS = 24
    /** pptx 最多渲染的页数 */
    const val MAX_SLIDES = 60
    /** 解包后单条目上限（防止 zip 炸弹把内存吃穿） */
    private const val MAX_ENTRY_BYTES = 24 * 1024 * 1024
    /** 单次解析总解压字符上限 */
    private const val MAX_TOTAL_BYTES = 48 * 1024 * 1024
    /** 解包条目数量上限 */
    private const val MAX_ZIP_ENTRIES = 1000

    fun isSupportedExtension(ext: String): Boolean {
        return ext.lowercase(Locale.ROOT) in setOf("xlsx", "pptx")
    }

    /**
     * 解析 OOXML 文件；不支持的扩展名或解析失败返回 null（调用方回落「外部打开」卡片）。
     */
    fun extract(bytes: ByteArray, fileName: String): OfficePreview? {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (!isSupportedExtension(ext)) return null
        return runCatching {
            val entries = readZipEntries(bytes)
            when (ext) {
                "xlsx" -> extractSheet(entries)
                "pptx" -> extractSlides(entries)
                else -> null
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- zip

    private fun readZipEntries(bytes: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        var entryCount = 0
        var totalBytes = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entryCount > MAX_ZIP_ENTRIES) break
                if (entry.isDirectory) continue
                val name = entry.name
                val needed = name == "xl/workbook.xml" ||
                    name == "xl/sharedStrings.xml" ||
                    (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) ||
                    (name.startsWith("ppt/slides/slide") && name.endsWith(".xml"))
                if (!needed) continue
                val text = readEntryText(zip)
                if (text != null) {
                    totalBytes += text.length
                    if (totalBytes > MAX_TOTAL_BYTES) break
                    result[name] = text
                }
            }
        }
        return result
    }

    private fun readEntryText(zip: ZipInputStream): String? {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = zip.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    // ---------------------------------------------------------------- xlsx

    private fun extractSheet(entries: Map<String, String>): OfficePreview? {
        val sheetEntry = entries.entries
            .filter { it.key.startsWith("xl/worksheets/") && it.key.endsWith(".xml") }
            .minByOrNull { sheetOrder(it.key) }
            ?: return null

        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheetName = entries["xl/workbook.xml"]
            ?.let { parseFirstSheetName(it) }
            ?: "Sheet1"

        val rows = ArrayList<List<String>>()
        var truncated = false
        for (rowBlock in xmlBlockList(sheetEntry.value, "row")) {
            if (rows.size >= MAX_ROWS) {
                truncated = true
                break
            }
            val cells = ArrayList<String>()
            for (cellBlock in xmlBlockList(rowBlock.inner, "c")) {
                val ref = attribute(cellBlock.openTag, "r").orEmpty()
                val colIndex = columnIndex(ref)
                if (colIndex < 0) continue
                if (colIndex >= MAX_COLS) {
                    truncated = true
                    continue
                }
                val type = attribute(cellBlock.openTag, "t").orEmpty()
                val value = when (type) {
                    "s" -> {
                        val idx = innerText(cellBlock.inner, "v").toIntOrNull() ?: -1
                        sharedStrings.getOrNull(idx).orEmpty()
                    }
                    // inlineStr 的文本在 <is><t>…</t></is> 或多个 <r><t>…</t></r> 里；其它一律取 <v>
                    "inlineStr" -> xmlTexts(cellBlock.inner, "t").joinToString("")
                    else -> innerText(cellBlock.inner, "v")
                }
                while (cells.size <= colIndex) cells.add("")
                cells[colIndex] = value
            }
            rows.add(cells)
        }

        if (rows.isEmpty()) return null
        // 去掉尾部全空行（Excel 常把格式刷到几千行）
        val trimmed = rows.dropLastWhile { row -> row.all { it.isBlank() } }
        if (trimmed.isEmpty()) return null
        return OfficePreview.Sheet(sheetName = sheetName, rows = trimmed, truncated = truncated)
    }

    private fun sheetOrder(path: String): Int {
        return path.substringAfterLast("sheet").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun parseSharedStrings(xml: String): List<String> {
        return xmlBlockList(xml, "si").map { block ->
            // 一个 <si> 里可能有多个 <r><t>…</t></r> 富文本片段，全部拼起来
            xmlTexts(block.inner, "t").joinToString("")
        }
    }

    private fun parseFirstSheetName(workbookXml: String): String? {
        val block = xmlBlockList(workbookXml, "sheet").firstOrNull() ?: return null
        return attribute(block.openTag, "name")
    }

    /** "B12" -> 1；解析不出来返回 -1 */
    private fun columnIndex(ref: String): Int {
        if (ref.isEmpty()) return -1
        var value = 0
        var seen = false
        for (ch in ref) {
            if (ch.isLetter()) {
                if (value > 1000) break
                value = value * 26 + (ch.uppercaseChar() - 'A' + 1)
                seen = true
            } else {
                break
            }
        }
        return if (seen) value - 1 else -1
    }

    // ---------------------------------------------------------------- pptx

    private fun extractSlides(entries: Map<String, String>): OfficePreview? {
        val slideEntries = entries.entries
            .filter { it.key.startsWith("ppt/slides/slide") && it.key.endsWith(".xml") }
            // 注意用 substringAfterLast：键里的 "slides" 目录本身也包含 "slide"
            .sortedBy { it.key.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
        if (slideEntries.isEmpty()) return null

        val slides = ArrayList<OfficePreview.Slide>()
        for ((_, xml) in slideEntries) {
            if (slides.size >= MAX_SLIDES) break
            val lines = xmlTexts(xml, "a:t")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            slides.add(OfficePreview.Slide(index = slides.size + 1, lines = lines))
        }
        if (slides.isEmpty()) return null
        return OfficePreview.Slides(slides)
    }

    // ---------------------------------------------------------------- xml 扫描

    /** 一个已定位的 XML 块：`openTag` 是 `<c r="A1" t="s">` 原文（含属性），`inner` 是标签内部 */
    private class XmlBlock(val openTag: String, val inner: String)

    /**
     * 取出所有 `<tag ...>...</tag>` 块。OOXML 里这些标签不会自我嵌套
     * （`row` 不套 `row`，`c` 不套 `c`，`si` 不套 `si`），所以顺序扫描足够，
     * 不需要引入 XML 解析器（Android 的 `javax.xml` 行为与单测环境不一致）。
     */
    private fun xmlBlockList(xml: String, tag: String): List<XmlBlock> {
        val open = "<$tag"
        val close = "</$tag>"
        val blocks = ArrayList<XmlBlock>()
        var cursor = 0
        while (true) {
            val start = xml.indexOf(open, cursor)
            if (start < 0) break
            val afterName = start + open.length
            // 必须是完整标签名：`<t` 不能匹配到 `<table`
            val boundary = xml.getOrNull(afterName)
            if (boundary != null && boundary != '>' && boundary != ' ' && boundary != '/' &&
                boundary != '\t' && boundary != '\n' && boundary != '\r'
            ) {
                cursor = afterName
                continue
            }
            var tagEnd = -1
            var quoteChar = '\u0000'
            var scan = afterName
            while (scan < xml.length) {
                val ch = xml[scan]
                if (quoteChar != '\u0000') {
                    if (ch == quoteChar) quoteChar = '\u0000'
                } else {
                    if (ch == '"' || ch == '\'') {
                        quoteChar = ch
                    } else if (ch == '>') {
                        tagEnd = scan
                        break
                    }
                }
                scan++
            }
            if (tagEnd < 0) break
            val openTag = xml.substring(start, tagEnd + 1)
            if (xml.getOrNull(tagEnd - 1) == '/') {
                // 自闭合标签：属性仍可能有意义（<sheet name="x"/>），内部为空
                blocks.add(XmlBlock(openTag, ""))
                cursor = tagEnd + 1
                continue
            }
            val end = xml.indexOf(close, tagEnd + 1)
            if (end < 0) break
            blocks.add(XmlBlock(openTag, xml.substring(tagEnd + 1, end)))
            cursor = end + close.length
        }
        return blocks
    }

    private fun xmlBlocks(xml: String, tag: String): List<String> {
        return xmlBlockList(xml, tag).map { it.inner }
    }

    private fun xmlTexts(xml: String, tag: String): List<String> {
        return xmlBlocks(xml, tag).map { unescapeXml(it) }
    }

    private fun innerText(xml: String, tag: String): String {
        return xmlTexts(xml, tag).firstOrNull().orEmpty()
    }

    private fun attribute(xml: String, name: String): String? {
        val key = "$name=\""
        val start = xml.indexOf(key)
        if (start >= 0) {
            val valueStart = start + key.length
            val valueEnd = xml.indexOf('"', valueStart)
            if (valueEnd > valueStart) return unescapeXml(xml.substring(valueStart, valueEnd))
        }
        val singleKey = "$name='"
        val s2 = xml.indexOf(singleKey)
        if (s2 >= 0) {
            val valueStart = s2 + singleKey.length
            val valueEnd = xml.indexOf('\'', valueStart)
            if (valueEnd > valueStart) return unescapeXml(xml.substring(valueStart, valueEnd))
        }
        return null
    }

    internal fun unescapeXml(text: String): String {
        if (text.indexOf('&') < 0) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch != '&') {
                sb.append(ch)
                i++
                continue
            }
            val semi = text.indexOf(';', i)
            if (semi < 0) {
                sb.append(ch)
                i++
                continue
            }
            val entity = text.substring(i + 1, semi)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.let { codePointToString(it) }
                entity.startsWith("#") ->
                    entity.substring(1).toIntOrNull()?.let { codePointToString(it) }
                else -> null
            }
            if (decoded == null) {
                sb.append(ch)
                i++
            } else {
                sb.append(decoded)
                i = semi + 1
            }
        }
        return sb.toString()
    }

    private fun codePointToString(codePoint: Int): String? {
        if (codePoint <= 0 || codePoint > 0x10FFFF) return null
        return runCatching { String(Character.toChars(codePoint)) }.getOrNull()
    }
}
