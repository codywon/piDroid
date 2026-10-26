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
        description = "按系统全局配置加载工具",
        toolNames = null
    ),
    CHAT_ONLY(
        id = "chat-only",
        displayName = "chat-only",
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
