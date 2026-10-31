package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingLevelTest {

    @Test
    fun `cleanLabel contains no token bracket specifications`() {
        ThinkingLevel.entries.forEach { level ->
            assertFalse(
                "Level ${level.name} cleanLabel '${level.cleanLabel}' must not contain parenthesis or token counts",
                level.cleanLabel.contains("(") || level.cleanLabel.contains(")") || level.cleanLabel.contains("k", ignoreCase = true)
            )
        }
        assertEquals("关闭思考", ThinkingLevel.OFF.cleanLabel)
        assertEquals("微弱", ThinkingLevel.MINIMAL.cleanLabel)
        assertEquals("轻度", ThinkingLevel.LOW.cleanLabel)
        assertEquals("适度", ThinkingLevel.MEDIUM.cleanLabel)
        assertEquals("深度", ThinkingLevel.HIGH.cleanLabel)
        assertEquals("超深", ThinkingLevel.XHIGH.cleanLabel)
        assertEquals("极限", ThinkingLevel.MAX.cleanLabel)
    }

    @Test
    fun `isHighBudget correctly flags high thinking levels for warning highlight`() {
        assertTrue(ThinkingLevel.HIGH.isHighBudget)
        assertTrue(ThinkingLevel.XHIGH.isHighBudget)
        assertTrue(ThinkingLevel.MAX.isHighBudget)

        assertFalse(ThinkingLevel.OFF.isHighBudget)
        assertFalse(ThinkingLevel.MINIMAL.isHighBudget)
        assertFalse(ThinkingLevel.LOW.isHighBudget)
        assertFalse(ThinkingLevel.MEDIUM.isHighBudget)
    }

    @Test
    fun `fromValue parses case insensitively and falls back to HIGH`() {
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.fromValue("off"))
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.fromValue("OFF"))
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromValue("medium"))
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.fromValue("unknown_value"))
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.fromValue(null))
    }

    @Test
    fun `capability guardrail determines when to downgrade to OFF`() {
        val visionModelNoThinking = ModelInfo(
            id = "qwen-vl",
            name = "Qwen VL",
            provider = "dashscope",
            input = listOf("text", "image"),
            supportsThinking = false
        )
        val deepseekWithThinking = ModelInfo(
            id = "deepseek-r1",
            name = "DeepSeek R1",
            provider = "deepseek",
            supportsThinking = true
        )

        // 当切到不支持思考的模型且当前不是 OFF 时，必须自动降级到 OFF 避免浪费
        fun shouldDowngradeToOff(model: ModelInfo, currentLevel: ThinkingLevel): Boolean {
            return !model.supportsThinking && currentLevel != ThinkingLevel.OFF
        }

        assertTrue(shouldDowngradeToOff(visionModelNoThinking, ThinkingLevel.HIGH))
        assertTrue(shouldDowngradeToOff(visionModelNoThinking, ThinkingLevel.MEDIUM))
        assertFalse(shouldDowngradeToOff(visionModelNoThinking, ThinkingLevel.OFF))
        assertFalse(shouldDowngradeToOff(deepseekWithThinking, ThinkingLevel.HIGH))
    }
}
