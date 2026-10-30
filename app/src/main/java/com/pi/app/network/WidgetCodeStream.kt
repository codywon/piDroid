package com.pi.app.network

/**
 * 从 `toolcall_delta` 的原始 JSON 分片里，流式抽出 `widget_code` 的字符串值。
 *
 * 为什么需要它：pi-generative-ui 的 `show_widget(title, widget_code, width, height)`
 * 在模型侧是**边生成边下发**的 —— pi-web 的 SSE 出口只剥掉累积快照（`partial`），
 * 增量 `delta`（原始 JSON token）是原样透传给客户端的（见
 * `pi-web/.next/server/chunks/` 下的打捆文件里，形如
 * `let {partial, ...h} = assistantMessageEvent` 的解构）。
 * 所以手机端拿到的是：
 *
 * ```
 * {"type":"toolcall_delta","delta":"\"<!DOCTYPE ht","id":"call_x","toolName":"show_widget"}
 * ```
 *
 * 把同一个 toolCallId 的 `delta` 拼起来就是完整的入参 JSON，但**拼接过程中它不是合法
 * JSON**（字符串可能停在一半、转义序列可能被劈成两半 `\u00` ｜ `e9`、`\"` ｜ `…`），
 * 因此不能用 `JsonParser` 反复解析，必须用一个带跨片状态的扫描器：
 *
 *  1. 键扫描阶段：只在 `depth == 1`（顶层对象内）认键，且必须**不在别的字符串里**，
 *     避免把某个字符串值里的 `"widget_code"` 误判成键；
 *  2. 值扫描阶段：增量反转义，遇到 `\uXXXX` 不完整、`\` 落在片尾时停下等下一片，
 *     不消耗半个转义序列；
 *  3. 结束条件：遇到未转义的 `"` —— 之后的内容（`,"width":800}`）不再关心。
 *
 * 设计取舍：
 * - 保留全部原始分片（`raw`），因为它天然被 `widget_code` 体量上界（数十 KB）约束，
 *   换来实现简单与「可重放」；
 * - 只认第一个 `widget_code` 键。工具入参是扁平对象，不会出现同键两次；
 * - 不做完整 JSON 解析（不验括号配平）：分片到达时括号本来就不配平。
 */
class WidgetCodeStream {

    /** 累积的原始 JSON 分片。 */
    private val raw = StringBuilder()

    /** 已经反转义出来的 `widget_code` 值。 */
    private val out = StringBuilder()

    // ── 键扫描状态（跨分片保留）──
    private var scanPos = 0
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var keyBuffer: StringBuilder? = null

    /** 已匹配到的键的右引号位置；等待它后面的 `:` 与值引号。 */
    private var pendingKeyEnd = -1

    // ── 值扫描状态 ──
    private var valuePos = -1

    /** 已经读到值字符串的收尾引号。 */
    var isComplete: Boolean = false
        private set

    /** 当前已解析出的 `widget_code`（未完成时是前缀）。 */
    fun value(): String = out.toString()

    /** 已收到的原始分片字节数，用于「已接收 xx KB」这类进度提示。 */
    fun rawLength(): Int = raw.length

    /**
     * 喂一个分片。返回 true 表示 `value()` 有增长。
     *
     * 幂等：同一分片重复喂会重复追加（调用方按 SSE 事件顺序喂一次即可）。
     */
    fun feed(chunk: String): Boolean {
        if (chunk.isEmpty() || isComplete) return false
        raw.append(chunk)

        if (valuePos < 0) {
            if (!locateValueStart()) return false
        }
        return consumeValue()
    }

    /**
     * 定位 `"widget_code" : "` 之后的值起点。
     *
     * 返回 false 表示「还需要更多分片」：可能是键还没扫完，也可能键已匹配但
     * `:` / 值引号还没到 —— 这时不能推进 `scanPos`，否则下个分片会从中间开始扫。
     */
    private fun locateValueStart(): Boolean {
        if (pendingKeyEnd >= 0) {
            val resolved = tryResolvePendingKey()
            if (resolved == RESOLVED) {
                valuePos = resolvedValuePos
                return true
            }
            if (resolved == NEED_MORE) return false
            // NOT_MATCH：继续往下扫
        }

        var i = scanPos
        while (i < raw.length) {
            val c = raw[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                    keyBuffer?.append(c)
                } else {
                    when (c) {
                        '\\' -> escaped = true
                        '"' -> {
                            inString = false
                            val key = keyBuffer
                            keyBuffer = null
                            if (key != null && key.contentEquals(TARGET_KEY)) {
                                pendingKeyEnd = i
                                scanPos = i
                                val resolved = tryResolvePendingKey()
                                if (resolved == RESOLVED) {
                                    valuePos = resolvedValuePos
                                    return true
                                }
                                if (resolved == NEED_MORE) return false
                            }
                        }
                        else -> keyBuffer?.append(c)
                    }
                }
                i++
                continue
            }
            when (c) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                '"' -> {
                    // 只有顶层对象里的字符串才可能是键
                    keyBuffer = if (depth == 1) StringBuilder() else null
                    inString = true
                    escaped = false
                }
            }
            i++
        }
        scanPos = i
        return false
    }

    /**
     * 键已匹配，尝试吃掉 `:` 与值起始引号。
     * 分片恰好在中间断开时返回 NEED_MORE 并保留 `pendingKeyEnd` 等下一片。
     */
    private fun tryResolvePendingKey(): Int {
        var j = pendingKeyEnd + 1
        while (j < raw.length && raw[j].isWhitespace()) j++
        if (j >= raw.length) return NEED_MORE
        if (raw[j] != ':') {
            pendingKeyEnd = -1
            scanPos = j
            return NOT_MATCH
        }
        j++
        while (j < raw.length && raw[j].isWhitespace()) j++
        if (j >= raw.length) return NEED_MORE
        if (raw[j] != '"') {
            pendingKeyEnd = -1
            scanPos = j
            return NOT_MATCH
        }
        resolvedValuePos = j + 1
        pendingKeyEnd = -1
        scanPos = j + 1
        return RESOLVED
    }

    private var resolvedValuePos = -1

    /** 增量反转义；遇到不完整的转义序列时停在原地等下一片。 */
    private fun consumeValue(): Boolean {
        var grew = false
        var i = valuePos
        while (i < raw.length) {
            val c = raw[i]
            if (c == '"') {
                isComplete = true
                i++
                break
            }
            if (c != '\\') {
                out.append(c)
                grew = true
                i++
                continue
            }
            if (i + 1 >= raw.length) break
            val e = raw[i + 1]
            var consumed = 2
            when (e) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 6 > raw.length) break
                    val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                    if (code == null) {
                        out.append(e)
                        consumed = 2
                    } else {
                        out.append(code.toChar())
                        consumed = 6
                    }
                }
                else -> out.append(e)
            }
            grew = true
            i += consumed
        }
        valuePos = i
        return grew
    }

    private companion object {
        const val TARGET_KEY = "widget_code"
        const val RESOLVED = 1
        const val NEED_MORE = 0
        const val NOT_MATCH = -1
    }
}
