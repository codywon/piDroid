package com.pi.app.data.model

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端「策略性拒绝」的中文解释。
 *
 * 三条原文都能让用户卡住，且都不该显示成 `HTTP 500`：
 * 运行中换工具、子代理工具被 profile 定死、只读权限挡掉没标 readOnlyHint 的 MCP 工具。
 */
class ToolErrorMessagesTest {

    @Test
    fun `running session refusal is explained`() {
        val text = ToolErrorMessages.explain("Cannot change tools while the session is running")
        assertTrue(text != null && text.contains("运行中"))
    }

    @Test
    fun `subagent refusal is explained`() {
        val text = ToolErrorMessages.explain("Subagent tool selection is fixed by its profile")
        assertTrue(text != null && text.contains("子代理"))
    }

    @Test
    fun `read only mcp block is explained`() {
        val raw = "This session uses a read-only tool selection, and the MCP server does not " +
            "mark \"mcp__wren__run_sql\" as read-only (readOnlyHint), so the call was blocked. " +
            "Switch the session to a preset that allows changes to use it."
        val text = ToolErrorMessages.explain(raw)
        assertTrue(text != null && text.contains("只读"))
    }

    @Test
    fun `unknown errors stay untouched so the caller can show the raw text`() {
        assertNull(ToolErrorMessages.explain("ENOENT: no such file or directory"))
        assertNull(ToolErrorMessages.explain(""))
        assertNull(ToolErrorMessages.explain(null))
        assertNull(ToolErrorMessages.explain("   "))
    }

    @Test
    fun `matching ignores case and surrounding text`() {
        assertNotNull(ToolErrorMessages.explain("HTTP 500: Subagent tool selection is fixed by its profile"))
    }
}
