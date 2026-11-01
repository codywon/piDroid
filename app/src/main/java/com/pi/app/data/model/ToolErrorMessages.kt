package com.pi.app.data.model

/**
 * 服务端「策略性拒绝」的人话翻译。
 *
 * 这三种拒绝都不是故障，而是 pi 按规则挡住了操作，原文是英文且很长：
 * - 会话运行中换工具（pi-web `rpc-manager.setRpcSessionTools`）
 * - 子代理的工具清单由它的 profile 定死（同上）
 * - 只读权限下调用没标 `readOnlyHint` 的 MCP 工具（`lib/mcp-read-only-policy.ts`）
 *
 * 统一收在这里，让「切权限失败」的 toast 和聊天里工具卡的错误提示用同一套口径。
 */
object ToolErrorMessages {

    const val RUNNING = "Cannot change tools while the session is running"
    const val SUBAGENT_FIXED = "Subagent tool selection is fixed by its profile"
    const val READ_ONLY_MCP = "read-only tool selection"

    /**
     * @return 命中已知拒绝时返回中文解释；不认识的原样交给调用方展示（返回 null）。
     */
    fun explain(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        return when {
            text.contains(SUBAGENT_FIXED, ignoreCase = true) ->
                "子代理的工具由它自己的 agent 配置决定，手机上不能改"
            text.contains(RUNNING, ignoreCase = true) ->
                "任务运行中不能切换权限模式，等本轮结束再切"
            text.contains(READ_ONLY_MCP, ignoreCase = true) ->
                "本会话是只读权限：只放行服务端标记为只读的 MCP 工具，要写操作先切到 default 或 full"
            else -> null
        }
    }
}
