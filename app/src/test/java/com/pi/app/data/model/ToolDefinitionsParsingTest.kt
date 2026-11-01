package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `get_tools` 响应解析（`POST /api/agent/{id}` `{"type":"get_tools"}`）。
 *
 * 只能靠 CI 验证的原因：包装层读错（`{success, data}` vs 顶层数组）时，面板会显示
 * 「本会话没有启用的工具」——和「真的没启用工具」长得一模一样，没有任何报错。
 */
class ToolDefinitionsParsingTest {

    @Test
    fun `rpc wrapper is unwrapped`() {
        val tools = ToolDefinitionsParsing.parse(
            """
            {"success":true,"data":[
              {"name":"read","description":"读取文件","active":true,"exposure":"direct",
               "parameters":{"type":"object","properties":{
                 "path":{"type":"string","description":"绝对路径"},
                 "limit":{"type":"number"}},
                 "required":["path"]}},
              {"name":"bash","description":"执行命令","active":false,"exposure":"direct",
               "parameters":{"type":"object","properties":{}}}
            ]}
            """.trimIndent()
        )
        assertEquals(2, tools?.size)
        val read = tools!!.first()
        assertEquals("read", read.name)
        assertTrue(read.active)
        assertEquals("direct", read.exposure)
        assertEquals(listOf("path", "limit"), read.parameters.map { it.name })
        assertEquals(listOf("path"), read.requiredParameters.map { it.name })
        assertEquals("2 个参数", read.parameterSummary)
        assertFalse(tools[1].active)
        assertEquals("无参数", tools[1].parameterSummary)
    }

    @Test
    fun `bare array is accepted for older gateways`() {
        val tools = ToolDefinitionsParsing.parse("""[{"name":"grep","active":true}]""")
        assertEquals(1, tools?.size)
        assertEquals("grep", tools?.first()?.name)
        assertEquals("无参数", tools?.first()?.parameterSummary)
    }

    @Test
    fun `declaration hidden is kept apart from inactive`() {
        val tools = ToolDefinitionsParsing.parse(
            """{"data":[{"name":"write","active":true,"declarationHidden":true}]}"""
        )
        val write = tools!!.single()
        assertTrue(write.active)
        assertTrue(write.declarationHidden)
    }

    @Test
    fun `error response is not reported as an empty tool list`() {
        assertNull(ToolDefinitionsParsing.parse("""{"error":"Session not found"}"""))
    }

    @Test
    fun `garbage does not crash and is reported as unparsable`() {
        assertNull(ToolDefinitionsParsing.parse(""))
        assertNull(ToolDefinitionsParsing.parse("<html>502</html>"))
        assertNull(ToolDefinitionsParsing.parse("""{"success":true}"""))
        assertNull(ToolDefinitionsParsing.parse("42"))
    }

    @Test
    fun `entries without a name are dropped`() {
        val tools = ToolDefinitionsParsing.parse("""{"data":[{"description":"no name"},{"name":""},{"name":"ls"}]}""")
        assertEquals(listOf("ls"), tools?.map { it.name })
    }

    @Test
    fun `empty list is a valid answer`() {
        assertEquals(emptyList<ToolDefinition>(), ToolDefinitionsParsing.parse("""{"success":true,"data":[]}"""))
    }
}
