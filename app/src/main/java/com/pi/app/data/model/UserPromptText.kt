package com.pi.app.data.model

/**
 * 「用户提问正文」的归一化规则（纯 JVM，可单测）。
 *
 * 为什么需要它（v1.8.67 仍存在）：带图片的提问，pi 内核会把图片缩放后**往正文末尾追加
 * 一行给模型看的尺寸提示**（`utils/image-process.ts` 的 `processImage` → `formatDimensionNote`），
 * 并且这一行会随消息一起落进会话 .jsonl。于是同一条提问在两端长得不一样：
 *
 * - 本机乐观气泡：用户原话 `如图，你现在用 FO-TR-S 标识…`
 * - 服务端快照：`如图，你现在用 FO-TR-S 标识…\n\n[Image: original 1220x2656, displayed at 919x2000. …]`
 *
 * 手机截图（1220×2656）几乎必然被缩放，所以**每次发截图都命中**。正文对不上带来的连锁反应：
 *
 * 1) [SessionSnapshotMerge.reconcileList] 认不出服务端那条就是本机这条提问，乐观气泡被当作
 *    in-flight 追加到列表末尾 —— 界面上就是「模型已经答完了，下面又冒出一条我发的内容」；
 * 2) [PendingPromptSettlement] 也认为它没送达，空闲后把它撤掉，甚至把正文又塞回输入框；
 * 3) 历史里那条提问的气泡直接把 `[Image: original …]` 这行机器话术原样显示给用户。
 *
 * 这些提示行是**给模型看的元数据，不是用户写的内容**：比较正文、回收气泡、渲染气泡、回填草稿
 * 时都应当先剥掉。
 */
object UserPromptText {

    /** 引导消息在本地气泡上带的前缀（见 MainActivity 的 onSendSteer）。 */
    const val STEER_PREFIX = "➔ [引导] "

    /**
     * pi 追加在正文末尾的图片提示行。三种来源（见 `utils/image-process.ts`）：
     * - `[Image: original WxH, displayed at WxH. Multiply coordinates by X to map to original image.]`
     * - `[Image converted from x to y.]`
     * - `[Image omitted: …]`
     */
    private val IMAGE_HINT_PATTERNS = listOf(
        Regex(
            """\[\s*Image:\s*original\s+\d+\s*x\s*\d+,\s*displayed at\s+\d+\s*x\s*\d+\.[^\[\]]*]""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""\[\s*Image converted from[^\[\]]*]""", RegexOption.IGNORE_CASE),
        Regex("""\[\s*Image omitted:[^\[\]]*]""", RegexOption.IGNORE_CASE)
    )

    /** 连续 3 个以上换行压成一个空行（提示行被剥掉后常留下多余空行）。 */
    private val EXTRA_BLANK_LINES = Regex("""\n{3,}""")

    /**
     * 剥掉 pi 追加的图片提示行，得到用户真正写下的正文。
     *
     * 只按提示行的完整格式匹配（前面必须真的是 `[Image`），用户正文里偶然出现方括号不会被动到。
     */
    fun stripImageHints(text: String): String {
        if (text.isEmpty()) return text
        if (!text.contains("[Image", ignoreCase = true)) return text
        var out = text
        for (pattern in IMAGE_HINT_PATTERNS) {
            out = pattern.replace(out, "")
        }
        return EXTRA_BLANK_LINES.replace(out, "\n\n").trim()
    }

    /**
     * 比较用的正文：剥掉图片提示行与引导前缀，去掉首尾空白。
     *
     * 引导消息在本地气泡上带 `➔ [引导] ` 前缀，服务端落盘的是原文，所以前缀也要去掉。
     */
    fun normalize(text: String): String =
        stripImageHints(text).trim().removePrefix(STEER_PREFIX).trim()

    /** 两条提问是否是同一句话（跨「本机乐观气泡 / 服务端快照」比较时使用）。 */
    fun samePrompt(a: String, b: String): Boolean = normalize(a) == normalize(b)
}
