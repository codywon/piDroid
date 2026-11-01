package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 工具权限模式的判定口径测试（1:1 对标 pi-web `lib/tool-presets.ts`）。
 *
 * 这些断言看起来琐碎，但每一个都对应过线上语义：
 * 把「没钉住」读成「仅聊天」、把 MCP 工具名混进判定、认不出来时默认 full，
 * 都会让用户看到一个与会话真实权限不符的开关。
 */
class ToolPresetTest {

    @Test
    fun `absent tool list means the session follows settings json`() {
        assertEquals(ToolPreset.CONFIGURED, ToolPreset.fromToolNames(null))
    }

    @Test
    fun `empty tool list means chat only`() {
        assertEquals(ToolPreset.CHAT_ONLY, ToolPreset.fromToolNames(emptyList()))
    }

    @Test
    fun `read only preset is recognised in any order`() {
        assertEquals(
            ToolPreset.READ_ONLY,
            ToolPreset.fromToolNames(listOf("ls", "find", "read", "grep"))
        )
    }

    @Test
    fun `default preset is recognised`() {
        assertEquals(
            ToolPreset.DEFAULT,
            ToolPreset.fromToolNames(listOf("write", "edit", "bash", "read"))
        )
    }

    @Test
    fun `full preset is recognised`() {
        assertEquals(
            ToolPreset.FULL,
            ToolPreset.fromToolNames(listOf("read", "bash", "edit", "write", "grep", "find", "ls"))
        )
    }

    @Test
    fun `powershell counts as bash so windows sessions read as the same preset`() {
        assertEquals(
            ToolPreset.DEFAULT,
            ToolPreset.fromToolNames(listOf("read", "powershell", "edit", "write"))
        )
    }

    @Test
    fun `tools outside the builtin list do not change the verdict`() {
        // MCP 工具混进钉住的清单里时，判定仍应落在内置工具那一档（pi-web 同样先过滤再比较）
        assertEquals(
            ToolPreset.READ_ONLY,
            ToolPreset.fromToolNames(listOf("read", "grep", "find", "ls", "mcp__wren__run_sql"))
        )
    }

    @Test
    fun `only non builtin tools falls back to default like pi-web`() {
        // pi-web: active 为空串，认不出任何预设，回落 default（不是 full，也不是 chat-only）
        assertEquals(
            ToolPreset.DEFAULT,
            ToolPreset.fromToolNames(listOf("mcp__wren__run_sql"))
        )
    }

    @Test
    fun `unknown tool combination falls back to default not full`() {
        assertEquals(
            ToolPreset.DEFAULT,
            ToolPreset.fromToolNames(listOf("read", "edit"))
        )
    }

    @Test
    fun `preset ids round trip`() {
        ToolPreset.entries.forEach { preset ->
            assertEquals(preset, ToolPreset.fromId(preset.id))
        }
    }

    @Test
    fun `unknown or missing preference falls back to configured`() {
        // 未知/脏值不能默默钉死一套工具，只能跟随 settings.json
        assertEquals(ToolPreset.CONFIGURED, ToolPreset.fromId(""))
        assertEquals(ToolPreset.CONFIGURED, ToolPreset.fromId("  "))
        assertEquals(ToolPreset.CONFIGURED, ToolPreset.fromId("legacy-super-mode"))
    }

    @Test
    fun `pi-web wire value for chat only is accepted`() {
        assertEquals(ToolPreset.CHAT_ONLY, ToolPreset.fromId("none"))
    }

    @Test
    fun `configured sends no override and pinned presets send their list`() {
        // configured 必须保持 null：报文里省略 toolNames 才是「撤销钉住」
        assertNull(ToolPreset.CONFIGURED.toolNames)
        assertEquals(emptyList<String>(), ToolPreset.CHAT_ONLY.toolNames)
        assertEquals(listOf("read", "grep", "find", "ls"), ToolPreset.READ_ONLY.toolNames)
        assertEquals(listOf("read", "bash", "edit", "write"), ToolPreset.DEFAULT.toolNames)
        assertEquals(
            listOf("bash", "read", "edit", "write", "grep", "find", "ls"),
            ToolPreset.FULL.toolNames
        )
    }

    @Test
    fun `every pinned preset maps back to itself when read from the session`() {
        listOf(ToolPreset.CHAT_ONLY, ToolPreset.READ_ONLY, ToolPreset.DEFAULT, ToolPreset.FULL)
            .forEach { preset ->
                assertEquals(preset, ToolPreset.fromToolNames(preset.toolNames))
            }
    }
}
