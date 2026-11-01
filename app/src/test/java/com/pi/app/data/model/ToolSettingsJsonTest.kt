package com.pi.app.data.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析 pi-web 的 `ToolSettingsResponse`（`GET/PUT /api/tools/settings`）。
 *
 * 这段解析曾经错得很隐蔽：写入是裸值，读回来是包装对象
 * （`{"codemodeMode":{"value":"only"}}`、`{"codemodeInlineBudget":{"value":8000}}`），
 * 未设置时是 `{}`。照着写入的形状去读回来，`asInt` 会直接抛异常。
 */
class ToolSettingsJsonTest {

    private fun parse(raw: String) = ToolSettings.fromJson(JsonParser.parseString(raw).asJsonObject)

    @Test
    fun `reads the wrapped response shape`() {
        val settings = parse(
            """
            {"isWindows":true,"powerShellEnabled":false,"codemode":"always",
             "codemodeMode":{"value":"only"},"codemodeInlineBudget":{"value":8000}}
            """.trimIndent()
        )
        assertTrue(settings.isWindows)
        assertFalse(settings.powerShellEnabled)
        assertEquals(ToolSettings.CODEMODE_ALWAYS, settings.codemode)
        assertEquals(ToolSettings.MODE_ONLY, settings.codemodeMode)
        assertTrue(settings.scriptMode)
        assertEquals(8000, settings.codemodeInlineBudget)
    }

    @Test
    fun `empty budget object means pi default not zero`() {
        val settings = parse(
            """
            {"codemode":"automatic","codemodeMode":{"value":"on"},"codemodeInlineBudget":{}}
            """.trimIndent()
        )
        assertNull(settings.codemodeInlineBudget)
        assertEquals(ToolSettings.DEFAULT_INLINE_BUDGET, settings.inlineBudgetOrDefault)
        assertFalse(settings.scriptMode)
    }

    @Test
    fun `invalid mode falls back to direct calls like pi does`() {
        // pi 读 codemode.mode 时只有恰好等于 "only" 才算脚本模式，其余一律按 "on"；
        // 服务端遇到脏值时只给 {invalid}、不给 {value}，这里必须落在 on 上。
        val settings = parse("""{"codemodeMode":{"invalid":"\"garbage\""}}""")
        assertEquals(ToolSettings.MODE_ON, settings.codemodeMode)
        assertFalse(settings.scriptMode)
    }

    @Test
    fun `flat values are still accepted`() {
        // 老网关直接给裸值时不至于整块设置读不出来
        val settings = parse("""{"codemode":"always","codemodeMode":"only","codemodeInlineBudget":15000}""")
        assertEquals(ToolSettings.CODEMODE_ALWAYS, settings.codemode)
        assertEquals(ToolSettings.MODE_ONLY, settings.codemodeMode)
        assertEquals(15000, settings.codemodeInlineBudget)
    }

    @Test
    fun `missing fields do not invent an always on code mode`() {
        val settings = parse("{}")
        assertEquals(ToolSettings.CODEMODE_AUTOMATIC, settings.codemode)
        assertEquals(ToolSettings.MODE_ON, settings.codemodeMode)
        assertNull(settings.codemodeInlineBudget)
        assertFalse(settings.isWindows)
        assertFalse(settings.powerShellEnabled)
    }
}
