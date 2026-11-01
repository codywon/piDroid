package com.pi.app.data.model

/**
 * 智能体运行权限模式与内置工具预设 (1:1 对标 pi-web `lib/tool-presets.ts`)
 *
 * 口径与上游完全一致：
 * - `configured` 不是工具清单，而是「不钉住本会话」——pi 按 settings.json 的
 *   defaultTools 自己解析，会话会一直跟随该配置变化；
 * - 其余四项把显式工具清单写进会话（空清单 = 仅聊天，pi 侧名为 `none`）。
 */
enum class ToolPreset(
    val id: String,
    val displayName: String,
    val description: String,
    val toolNames: List<String>?
) {
    CONFIGURED(
        id = "configured",
        displayName = "configured",
        description = "取自 settings.json 的 defaultTools",
        toolNames = null
    ),
    CHAT_ONLY(
        id = "chat-only",
        displayName = "仅聊天",
        description = "禁用所有外部工具，纯文本问答",
        toolNames = emptyList()
    ),
    READ_ONLY(
        id = "read-only",
        displayName = "read-only",
        description = "仅允许查看与搜索文件",
        toolNames = listOf("read", "grep", "find", "ls")
    ),
    DEFAULT(
        id = "default",
        displayName = "default",
        description = "允许读写文件与执行终端命令",
        toolNames = listOf("read", "bash", "edit", "write")
    ),
    FULL(
        id = "full",
        displayName = "full",
        description = "开放所有 7 项内置能力",
        toolNames = listOf("bash", "read", "edit", "write", "grep", "find", "ls")
    );

    companion object {
        /**
         * 参与预设判定的工具名（pi-web `BUILTIN_TOOL_NAMES`）。
         * MCP / 扩展工具不在此列：它们由 toolExposure 与只读策略单独管，
         * 混进来只会让「这个会话是什么预设」判错。
         */
        private val BUILTIN_TOOL_NAMES =
            setOf("bash", "read", "edit", "write", "grep", "find", "ls", "powershell")

        /** pi-web 里「仅聊天」在协议/存储上的值是 `none`，本机偏好沿用旧 id `chat-only`。 */
        private const val CHAT_ONLY_ALIAS = "none"

        /**
         * 旧偏好值/未知值一律回到 [CONFIGURED]：跟随服务端配置永远不会比
         * 「默默钉死一套工具」更危险。上游未识别清单同样回落 `default`，两者不冲突：
         * 这里是「读本机存的预设 id」，那里是「读会话里钉住的工具名」。
         */
        fun fromId(id: String): ToolPreset {
            val trimmed = id.trim()
            if (trimmed.equals(CHAT_ONLY_ALIAS, ignoreCase = true)) return CHAT_ONLY
            return entries.find { it.id.equals(trimmed, ignoreCase = true) } ?: CONFIGURED
        }

        /**
         * 会话里钉住的工具清单 → 预设，逐行对标 pi-web `getPresetFromToolNames`：
         * `null` = 没有钉住（configured）、空清单 = 仅聊天、`powershell` 归一成 `bash`、
         * 只保留内置工具名后排序比较，认不出来的一律 `default`。
         */
        fun fromToolNames(names: List<String>?): ToolPreset {
            if (names == null) return CONFIGURED
            if (names.isEmpty()) return CHAT_ONLY
            val active = names
                .map { if (it == "powershell") "bash" else it }
                .filter { it in BUILTIN_TOOL_NAMES }
                .sorted()
            return when {
                active == READ_ONLY.toolNames.orEmpty().sorted() -> READ_ONLY
                active == DEFAULT.toolNames.orEmpty().sorted() -> DEFAULT
                active == FULL.toolNames.orEmpty().sorted() -> FULL
                else -> DEFAULT
            }
        }
    }
}

data class SetToolsResult(
    val sessionId: String,
    val recreated: Boolean
)
