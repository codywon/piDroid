package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码模式设置的写入报文（1:1 对标 pi-web `PUT /api/tools/settings`）。
 *
 * 上游严格要求「一次只改一项」：`1 !== CHANGES.filter(...)` 直接 400。
 * 这里锁住的就是这条契约 —— 以前是打包成 `{action:"set-codemode", ...}` 发给 `/api/mcp`，
 * 那个 action 从来不存在，等于一次都没保存成功。
 */
class ToolSettingsChangeTest {

    @Test
    fun `codemode change carries only the codemode field`() {
        assertEquals(
            mapOf<String, Any>("codemode" to "always"),
            ToolSettingsChange.Codemode("always").toPayload()
        )
    }

    @Test
    fun `mode change carries only the codemodeMode field`() {
        assertEquals(
            mapOf<String, Any>("codemodeMode" to "only"),
            ToolSettingsChange.Mode(ToolSettings.MODE_ONLY).toPayload()
        )
    }

    @Test
    fun `budget change carries only the inline budget field`() {
        assertEquals(
            mapOf<String, Any>("codemodeInlineBudget" to 8000),
            ToolSettingsChange.InlineBudget(8000).toPayload()
        )
    }

    @Test
    fun `settings map onto the code mode config the panel shows`() {
        val settings = ToolSettings(
            codemode = ToolSettings.CODEMODE_ALWAYS,
            codemodeMode = ToolSettings.MODE_ONLY,
            codemodeInlineBudget = 5000
        )
        val config = settings.toCodeModeConfig()
        assertEquals("always", config.mode)
        assertTrue(config.scriptMode)
        assertEquals(5000, config.tokenBudget)
    }

    @Test
    fun `unset budget shows pi default instead of inventing one`() {
        // codemodeInlineBudget 缺失 = 跟随 pi 默认值 3000，不是「没读到」
        val settings = ToolSettings(codemodeInlineBudget = null)
        assertEquals(ToolSettings.DEFAULT_INLINE_BUDGET, settings.inlineBudgetOrDefault)
        assertEquals(3000, settings.toCodeModeConfig().tokenBudget)
    }

    @Test
    fun `direct tool mode is the opposite of script mode`() {
        assertFalse(ToolSettings(codemodeMode = ToolSettings.MODE_ON).scriptMode)
        assertTrue(ToolSettings(codemodeMode = ToolSettings.MODE_ONLY).scriptMode)
    }
}
