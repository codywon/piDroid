package com.pi.app.data.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSanityTest {

    @Test
    fun `SkillInfo isProjectScope trusts explicit scope over category fallback`() {
        val explicitGlobalWithProjectCategory = SkillInfo(
            name = "demo",
            scope = "global",
            category = "项目技能"
        )
        assertFalse(explicitGlobalWithProjectCategory.isProjectScope)

        val explicitProject = SkillInfo(
            name = "demo",
            scope = "project",
            category = "全局技能"
        )
        assertTrue(explicitProject.isProjectScope)

        val emptyScopeFallsBackToCategory = SkillInfo(
            name = "demo",
            scope = "",
            category = "工作区技能"
        )
        assertTrue(emptyScopeFallsBackToCategory.isProjectScope)
    }

    @Test
    fun `McpServerInfo isConnected uses runtime state when statusObj is present`() {
        val disconnectedEvenIfEnabled = McpServerInfo(
            name = "fs",
            enabled = true,
            statusObj = McpServerStatus(state = "error")
        )
        assertFalse(disconnectedEvenIfEnabled.isConnected)

        val connectedWithStatus = McpServerInfo(
            name = "fs",
            enabled = false,
            statusObj = McpServerStatus(state = "connected")
        )
        assertTrue(connectedWithStatus.isConnected)

        val noStatusFallsBackToEnabled = McpServerInfo(
            name = "fs",
            enabled = true,
            statusObj = null
        )
        assertTrue(noStatusFallsBackToEnabled.isConnected)
    }

    @Test
    fun `parseProvidersConfig degrades gracefully when fields are non-primitive json values`() {
        val json = """
            {
              "providers": {
                "broken-types": {
                  "baseUrl": {"unexpected": true},
                  "apiKey": ["array"],
                  "api": "openai-completions",
                  "models": [
                    {
                      "id": "m-1",
                      "name": {"nested": 1},
                      "reasoning": {"flag": true},
                      "contextWindow": "not-a-number",
                      "maxTokens": 4096
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val root = JsonParser.parseString(json).asJsonObject
        val parsed = ModelConfigConverter.parseProvidersConfig(root)
        assertEquals(1, parsed.size)
        val prov = parsed[0]
        assertEquals("broken-types", prov.name)
        assertEquals("", prov.baseUrl)
        assertEquals("", prov.apiKey)
        assertEquals(1, prov.models.size)
        assertEquals("m-1", prov.models[0].id)
        assertFalse(prov.models[0].reasoning)
        assertEquals(4096L, prov.models[0].maxTokens)
    }
}
