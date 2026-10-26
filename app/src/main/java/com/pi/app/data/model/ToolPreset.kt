package com.pi.app.data.model

/**
 * 智能体运行权限模式与内置工具预设 (1:1 严格对标 pi-web Tool Presets 体系)
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
        displayName = "chat-only",
        description = "仅聊天",
        toolNames = emptyList()
    ),
    READ_ONLY(
        id = "read-only",
        displayName = "read-only",
        description = "4 个只读内置工具",
        toolNames = listOf("read", "grep", "find", "ls")
    ),
    DEFAULT(
        id = "default",
        displayName = "default",
        description = "4 个内置工具",
        toolNames = listOf("read", "bash", "edit", "write")
    ),
    FULL(
        id = "full",
        displayName = "full",
        description = "全部内置工具",
        toolNames = listOf("bash", "read", "edit", "write", "grep", "find", "ls")
    );

    companion object {
        fun fromId(id: String): ToolPreset {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: FULL
        }

        fun fromToolNames(names: List<String>?): ToolPreset {
            if (names == null) return CONFIGURED
            if (names.isEmpty()) return CHAT_ONLY
            val set = names.map { if (it == "powershell") "bash" else it }.toSet()
            val readOnlySet = setOf("read", "grep", "find", "ls")
            val defaultSet = setOf("read", "bash", "edit", "write")
            val fullSet = setOf("bash", "read", "edit", "write", "grep", "find", "ls")

            return when {
                set == readOnlySet -> READ_ONLY
                set == defaultSet -> DEFAULT
                set == fullSet -> FULL
                else -> DEFAULT
            }
        }
    }
}

data class SetToolsResult(
    val sessionId: String,
    val recreated: Boolean
)
