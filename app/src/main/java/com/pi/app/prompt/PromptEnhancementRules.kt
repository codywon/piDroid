package com.pi.app.prompt

/**
 * 输入框提示词增强的数据与纯规则。
 *
 * 这个文件刻意不依赖 Android/网络，方便在 `app/src/test` 用纯 JVM 测试覆盖：
 * 「哪些结果可以回填」「哪些输入不允许增强」「上下文怎么裁剪」这三件事最容易出
 * 静默错误，因此必须可单测。
 */

/** 服务端能力探测结果（`GET /api/ping` 的 `capabilities.promptEnhancement`）。 */
data class PromptEnhancementCapability(
    val version: Int = 0,
    val available: Boolean = false,
    val maxDraftChars: Int = PromptEnhancementRules.MAX_DRAFT_CHARS,
    val maxContextChars: Int = PromptEnhancementRules.MAX_CONTEXT_TOTAL_CHARS
)

/** 增强请求要打到哪台设备、用哪个模型（连接或模型变化后旧结果必须作废）。 */
data class PromptEnhancementTarget(
    val connectionKey: String,
    val provider: String,
    val modelId: String
) {
    val modelKey: String get() = PromptEnhancementRules.modelKey(provider, modelId)
}

/** 上下文条目（只包含用户/助手正文，不含思考、工具与附件）。 */
data class PromptEnhancementContextMessage(
    val role: String,
    val text: String
)

/** 发起增强时冻结的身份与修订号；只有全部仍匹配才允许回填。 */
data class PromptEnhancementTicket(
    val requestId: String,
    val connectionKey: String,
    val sessionId: String,
    val draftRevision: Long,
    val branchRevision: Long,
    val modelKey: String
)

/** 请求返回那一刻的界面状态，用于判定回填是否过期。 */
data class PromptEnhancementEnvironment(
    val connectionKey: String,
    val sessionId: String,
    val draftRevision: Long,
    val branchRevision: Long,
    val modelKey: String
)

/** 服务端返回的增强结果。 */
data class PromptEnhancementResult(
    val requestId: String,
    val draftRevision: Long,
    val branchRevision: Long,
    val enhancedText: String,
    val changed: Boolean,
    val provider: String,
    val modelId: String,
    val contextUsed: Boolean
)

/** 一次可撤销的增强：连接、原文与增强后文本（只有当前文本仍等于增强结果才可撤销）。 */
data class PromptEnhancementUndoState(
    val connectionKey: String,
    val original: String,
    val enhanced: String
)

/** 客户端发给伴侣网关的一次增强请求（与 HTTP 契约字段一一对应）。 */
data class PromptEnhancementRequest(
    val requestId: String,
    val sessionId: String,
    val draftRevision: Long,
    val branchRevision: Long,
    val target: PromptEnhancementTarget,
    val draft: String,
    val context: List<PromptEnhancementContextMessage>
)

/** 客户端可读的错误：`code` 与服务端错误码一致，便于区分「取消/限流/凭据」。 */
class PromptEnhancementException(val code: String, message: String) : java.io.IOException(message)

/**
 * 能力探测解析（纯 JVM，可单测）。
 *
 * 旧网关 / 直连 pi-web 没有 `capabilities` 字段，返回 null → 入口隐藏。
 * 任何字段类型不符也返回 null：宁可少一个入口，也不给一个点下去必然失败的按钮。
 */
object PromptEnhancementCapabilityParser {
    fun parse(body: String?): PromptEnhancementCapability? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val root = com.google.gson.JsonParser.parseString(body).asJsonObject
            val capabilities = root.get("capabilities")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@runCatching null
            val cap = capabilities.get("promptEnhancement")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@runCatching null
            PromptEnhancementCapability(
                version = cap.get("version")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                available = cap.get("available")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                maxDraftChars = cap.get("maxDraftChars")?.takeIf { !it.isJsonNull }?.asInt
                    ?: PromptEnhancementRules.MAX_DRAFT_CHARS,
                maxContextChars = cap.get("maxContextChars")?.takeIf { !it.isJsonNull }?.asInt
                    ?: PromptEnhancementRules.MAX_CONTEXT_TOTAL_CHARS
            )
        }.getOrNull()
    }
}

object PromptEnhancementRules {
    const val MAX_DRAFT_CHARS = 6000
    const val MAX_CONTEXT_MESSAGES = 10
    const val MAX_CONTEXT_MESSAGE_CHARS = 800
    const val MAX_CONTEXT_TOTAL_CHARS = 4000

    fun modelKey(provider: String, modelId: String): String = "${provider.trim()}:${modelId.trim()}"

    /**
     * 首版禁止增强的输入。返回 null 表示允许；返回文案用于按钮提示/TalkBack。
     *
     * 这里与服务端校验保持同一套判据（服务端仍会独立校验一遍，不信任客户端）。
     */
    fun blockedReason(text: String, maxDraftChars: Int = MAX_DRAFT_CHARS): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "先输入内容"
        if (trimmed.startsWith("/")) return "命令不参与增强"
        if (trimmed.startsWith("!")) return "shell 指令不参与增强"
        if (hasUnclosedFence(trimmed)) return "代码围栏未闭合"
        if (trimmed.codePointCount(0, trimmed.length) > maxDraftChars) return "草稿超过 $maxDraftChars 字上限"
        return null
    }

    private fun hasUnclosedFence(text: String): Boolean {
        // 三个反引号为一组围栏标记，出现奇数次即为未闭合
        var count = 0
        var index = 0
        while (true) {
            val found = text.indexOf("```", index)
            if (found < 0) break
            count += 1
            index = found + 3
        }
        return count % 2 == 1
    }

    /**
     * 真实模型返回的结果是否仍对应当前草稿与分支。
     *
     * 不能只比较文本：用户可能编辑后又改回原文，那也是一次新的修订，
     * 旧请求的结果不得再覆盖它。
     */
    fun canApply(
        ticket: PromptEnhancementTicket,
        result: PromptEnhancementResult,
        current: PromptEnhancementEnvironment
    ): Boolean {
        if (result.requestId != ticket.requestId) return false
        if (result.draftRevision != ticket.draftRevision) return false
        if (result.branchRevision != ticket.branchRevision) return false
        if (current.connectionKey != ticket.connectionKey) return false
        if (current.sessionId != ticket.sessionId) return false
        if (current.draftRevision != ticket.draftRevision) return false
        if (current.branchRevision != ticket.branchRevision) return false
        if (current.modelKey != ticket.modelKey) return false
        if (result.enhancedText.isBlank()) return false
        return true
    }

    /** 撤销只在「当前文本仍是这次增强的结果」时可用，避免覆盖用户后续修改。 */
    fun canUndo(undo: PromptEnhancementUndoState?, currentText: String, connectionKey: String): Boolean {
        if (undo == null) return false
        if (undo.connectionKey != connectionKey) return false
        if (undo.enhanced != currentText) return false
        return undo.original != undo.enhanced
    }

    /**
     * 从最近的对话里挑上下文：从最新往前取，超预算就停；单条过长保留开头。
     *
     * 只保留用户/助手正文，避免把工具日志、思考或附件体积带进提示词。
     * @param candidates 按时间正序（旧 → 新）
     */
    fun selectContext(
        candidates: List<PromptEnhancementContextMessage>,
        maxMessages: Int = MAX_CONTEXT_MESSAGES,
        maxTotalChars: Int = MAX_CONTEXT_TOTAL_CHARS,
        maxPerMessageChars: Int = MAX_CONTEXT_MESSAGE_CHARS
    ): List<PromptEnhancementContextMessage> {
        if (maxMessages <= 0 || maxTotalChars <= 0) return emptyList()
        val picked = ArrayDeque<PromptEnhancementContextMessage>()
        var used = 0
        for (index in candidates.indices.reversed()) {
            val candidate = candidates[index]
            val text = candidate.text.trim()
            if (text.isEmpty()) continue
            if (candidate.role != "user" && candidate.role != "assistant") continue
            val clipped = clip(text, maxPerMessageChars)
            val cost = clipped.codePointCount(0, clipped.length)
            if (used + cost > maxTotalChars) break
            used += cost
            picked.addFirst(PromptEnhancementContextMessage(role = candidate.role, text = clipped))
            if (picked.size >= maxMessages) break
        }
        return picked.toList()
    }

    private fun clip(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val count = text.codePointCount(0, text.length)
        if (count <= maxChars) return text
        val end = text.offsetByCodePoints(0, maxChars)
        return text.substring(0, end) + "…"
    }
}
