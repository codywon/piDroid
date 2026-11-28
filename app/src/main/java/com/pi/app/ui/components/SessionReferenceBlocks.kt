package com.pi.app.ui.components

/**
 * pi-web 的 `#` 会话引用块（`<session-reference …>`）的解析（纯 JVM，可单测）。
 *
 * 为什么手机端必须自己处理：引用是在**服务端**展开的（pi-web `lib/rpc-manager.ts` 的
 * prompt 路径：`expandSessionReferences()`），落进会话文件的就是展开后的文本，形如
 *
 * ```
 * <session-reference title="…" id="…" path="…" cwd="…" modified="…" messages="N" omitted="M">
 * Quoted from an earlier conversation for background only; it is not part of this request.
 * The whole session is in <path>
 *
 * <被引用会话的正文>
 * </session-reference>
 * ```
 *
 * 网页端把它折叠成一行并给出链接；直接交给 Markdown 渲染则会变成
 * 「一张 html 代码卡（属性串）→ 裸正文 → 一张闭合标签卡」，一段对话被打散成三块。
 *
 * 属性按上游 `attribute()` 做 HTML 转义（`&amp; &lt; &gt; &quot;`），这里做对应还原。
 * 找不到配对的闭合标签时整段原样保留 —— 宁可少折叠，也不能把正文吞掉。
 */
sealed interface MessageSegment {
    /** 普通正文，交给 Markdown 渲染 */
    data class Markdown(val text: String) : MessageSegment

    /** 一条被引用的会话 */
    data class Reference(val quote: QuotedSession) : MessageSegment
}

/** 块里的一个被引用会话（`title` 可能为空：未命名会话）。 */
data class QuotedSession(
    val title: String = "",
    val id: String = "",
    val path: String = "",
    val cwd: String = "",
    val modified: String = "",
    val messageCount: Int? = null,
    val omittedMessages: Int? = null,
    /** 被引用会话的正文（已去掉上游那行英文说明） */
    val body: String = ""
) {
    /** 折叠时显示的标题：上游对未命名会话用 `(untitled session)`。 */
    val displayTitle: String
        get() = title.trim().ifBlank { "未命名会话" }
}

object SessionReferenceBlocks {

    private const val OPEN = "<session-reference"
    private const val CLOSE = "</session-reference>"

    /** 上游写在正文前的那行英文说明（给模型的，界面不需要重复一遍） */
    private const val NOTE_PREFIX = "Quoted from an earlier conversation"

    /**
     * 把一条消息切成「普通正文 / 引用块」的有序片段。
     *
     * 没有引用块时返回单个 [MessageSegment.Markdown]。空文本返回空列表。
     */
    fun split(text: String): List<MessageSegment> {
        if (text.isBlank()) return emptyList()
        if (!text.contains(OPEN)) return listOf(MessageSegment.Markdown(text))

        val segments = mutableListOf<MessageSegment>()
        var cursor = 0
        while (true) {
            val open = text.indexOf(OPEN, cursor)
            if (open < 0) break
            val tagEnd = text.indexOf('>', open)
            if (tagEnd < 0) break
            val close = text.indexOf(CLOSE, tagEnd + 1)
            if (close < 0) break

            if (open > cursor) segments.add(MessageSegment.Markdown(text.substring(cursor, open)))
            segments.add(
                MessageSegment.Reference(
                    parseBlock(text.substring(open + OPEN.length, tagEnd), text.substring(tagEnd + 1, close))
                )
            )
            cursor = close + CLOSE.length
        }
        if (cursor == 0) return listOf(MessageSegment.Markdown(text))
        if (cursor < text.length) segments.add(MessageSegment.Markdown(text.substring(cursor)))
        return segments
    }

    /** 把整条消息压成一行可读文本（用于会话搜索命中、复制等不需要折叠的场景）。 */
    fun toPlainText(text: String): String = split(text).joinToString(" ") { segment ->
        when (segment) {
            is MessageSegment.Markdown -> segment.text
            is MessageSegment.Reference -> "〔引用会话：${segment.quote.displayTitle}〕"
        }
    }.trim()

    private fun parseBlock(attributesRaw: String, bodyRaw: String): QuotedSession {
        val attributes = parseAttributes(attributesRaw)
        return QuotedSession(
            title = attributes["title"].orEmpty(),
            id = attributes["id"].orEmpty(),
            path = attributes["path"].orEmpty(),
            cwd = attributes["cwd"].orEmpty(),
            modified = attributes["modified"].orEmpty(),
            messageCount = attributes["messages"]?.toIntOrNull(),
            omittedMessages = attributes["omitted"]?.toIntOrNull(),
            body = stripNote(bodyRaw)
        )
    }

    /** `key="value"` 对；值里的引号在转义阶段已变成 `&quot;`，不会截断。 */
    private fun parseAttributes(raw: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < raw.length) {
            while (i < raw.length && (raw[i].isWhitespace())) i++
            val keyStart = i
            while (i < raw.length && raw[i] != '=' && !raw[i].isWhitespace()) i++
            val key = raw.substring(keyStart, i)
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length || raw[i] != '=') continue
            i++ // skip '='
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length || raw[i] != '"') continue
            i++ // skip opening quote
            val valueStart = i
            while (i < raw.length && raw[i] != '"') i++
            if (key.isNotEmpty()) out[key] = unescape(raw.substring(valueStart, i))
            i++ // skip closing quote
        }
        return out
    }

    /** 与上游 `attribute()` 的转义顺序相反：`&amp;` 最后还原，避免二次解码。 */
    private fun unescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    /**
     * 去掉上游写的那行说明（连同它后面的空行）。
     *
     * 只在首行确实是那句说明时动手：引用块正文的第一行也可能是别的英文，
     * 不该被猜着删。
     */
    private fun stripNote(body: String): String {
        val trimmedStart = body.trimStart('\n', '\r', ' ', '\t')
        if (!trimmedStart.startsWith(NOTE_PREFIX)) return trimmedStart.trimEnd()
        val newline = trimmedStart.indexOf('\n')
        val rest = if (newline < 0) "" else trimmedStart.substring(newline + 1)
        return rest.trim('\n', '\r', ' ', '\t').trimEnd()
    }
}
