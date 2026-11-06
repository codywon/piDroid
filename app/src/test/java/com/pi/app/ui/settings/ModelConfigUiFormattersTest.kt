package com.pi.app.ui.settings

import com.pi.app.data.model.ModelConfigItem
import com.pi.app.data.model.ProviderConfigItem
import com.pi.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigUiFormattersTest {

    @Test
    fun `formatThinkingBudgetLabel returns expected label for all 7 levels`() {
        assertEquals("off · 关闭思考", ModelConfigUiFormatters.formatThinkingBudgetLabel(ThinkingLevel.OFF))
        assertEquals("minimal · 512 预算", ModelConfigUiFormatters.formatThinkingBudgetLabel(ThinkingLevel.MINIMAL))
        assertEquals("low · 1k 预算", ModelConfigUiFormatters.formatThinkingBudgetLabel(ThinkingLevel.LOW))
        assertEquals("medium · 4k 预算", ModelConfigUiFormatters.formatThinkingBudgetLabel(ThinkingLevel.MEDIUM))
        assertEquals("high · 16k 预算", ModelConfigUiFormatters.formatThinkingBudgetLabel(ThinkingLevel.HIGH))
        assertEquals("xhigh · 32k 预算", ModelConfigUiFormatters.formatThinkingBudgetLabel(ThinkingLevel.XHIGH))
        assertEquals("max · 64k 预算", ModelConfigUiFormatters.formatThinkingBudgetLabel(ThinkingLevel.MAX))
    }

    @Test
    fun `formatModelTokenSpec formats decimal and binary token capacities cleanly`() {
        assertEquals("—", ModelConfigUiFormatters.formatModelTokenSpec(0L))
        assertEquals("—", ModelConfigUiFormatters.formatModelTokenSpec(-100L))
        assertEquals("512", ModelConfigUiFormatters.formatModelTokenSpec(512L))

        // 1024-based presets
        assertEquals("4k", ModelConfigUiFormatters.formatModelTokenSpec(4_096L))
        assertEquals("8k", ModelConfigUiFormatters.formatModelTokenSpec(8_192L))
        assertEquals("16k", ModelConfigUiFormatters.formatModelTokenSpec(16_384L))
        assertEquals("32k", ModelConfigUiFormatters.formatModelTokenSpec(32_768L))
        assertEquals("64k", ModelConfigUiFormatters.formatModelTokenSpec(65_536L))
        assertEquals("128k", ModelConfigUiFormatters.formatModelTokenSpec(131_072L))
        assertEquals("1M", ModelConfigUiFormatters.formatModelTokenSpec(1_048_576L))

        // 1000-based presets
        assertEquals("32k", ModelConfigUiFormatters.formatModelTokenSpec(32_000L))
        assertEquals("128k", ModelConfigUiFormatters.formatModelTokenSpec(128_000L))
        assertEquals("200k", ModelConfigUiFormatters.formatModelTokenSpec(200_000L))
        assertEquals("1M", ModelConfigUiFormatters.formatModelTokenSpec(1_000_000L))
        assertEquals("2M", ModelConfigUiFormatters.formatModelTokenSpec(2_000_000L))
        assertEquals("1.5M", ModelConfigUiFormatters.formatModelTokenSpec(1_500_000L))
    }

    @Test
    fun `isPresetSelected matches both exact and equivalent k-M representations`() {
        assertTrue(ModelConfigUiFormatters.isPresetSelected(128_000L, 128_000L, "128k"))
        assertTrue(ModelConfigUiFormatters.isPresetSelected(131_072L, 128_000L, "128k"))
        assertTrue(ModelConfigUiFormatters.isPresetSelected(16_384L, 16_384L, "16k"))
        assertTrue(ModelConfigUiFormatters.isPresetSelected(16_000L, 16_384L, "16k"))
        assertFalse(ModelConfigUiFormatters.isPresetSelected(0L, 16_384L, "16k"))
        assertFalse(ModelConfigUiFormatters.isPresetSelected(32_000L, 128_000L, "128k"))
    }

    @Test
    fun `formatModelRowSummary omits duplicate model id when name equals id`() {
        val withDistinctName = ModelConfigItem(
            id = "claude-sonnet-4-5",
            name = "Claude 4.5 Sonnet",
            contextWindow = 200_000L,
            maxTokens = 16_384L
        )
        assertEquals(
            "Claude 4.5 Sonnet · 上下文 200k / 输出 16k",
            ModelConfigUiFormatters.formatModelRowSummary(withDistinctName)
        )

        val withSameName = ModelConfigItem(
            id = "deepseek-r1",
            name = "deepseek-r1",
            contextWindow = 128_000L,
            maxTokens = 8_192L
        )
        assertEquals(
            "上下文 128k / 输出 8k",
            ModelConfigUiFormatters.formatModelRowSummary(withSameName)
        )

        val withBlankName = ModelConfigItem(
            id = "gpt-5.4",
            name = "   ",
            contextWindow = 1_000_000L,
            maxTokens = 65_536L
        )
        assertEquals(
            "上下文 1M / 输出 64k",
            ModelConfigUiFormatters.formatModelRowSummary(withBlankName)
        )
    }

    @Test
    fun `nextCustomModelId increments suffix when collisions exist`() {
        assertEquals("custom-model", ModelConfigUiFormatters.nextCustomModelId(emptyList()))

        val oneExisting = listOf(ModelConfigItem(id = "custom-model"))
        assertEquals("custom-model-1", ModelConfigUiFormatters.nextCustomModelId(oneExisting))

        val multipleExisting = listOf(
            ModelConfigItem(id = "custom-model"),
            ModelConfigItem(id = "CUSTOM-MODEL-1"),
            ModelConfigItem(id = "custom-model-2")
        )
        assertEquals("custom-model-3", ModelConfigUiFormatters.nextCustomModelId(multipleExisting))
    }

    @Test
    fun `updateModelAt and removeModelAt isolate mutations to target index even when IDs collide`() {
        val initial = listOf(
            ModelConfigItem(id = "gpt-4o", name = "GPT-4o Original", contextWindow = 128_000L),
            ModelConfigItem(id = "custom-model", name = "Custom", contextWindow = 32_000L)
        )

        // User types "gpt-4o" into index 1 (transient collision on the way to "gpt-4o-mini")
        val step1 = ModelConfigUiFormatters.updateModelAt(initial, 1) { it.copy(id = "gpt-4o") }
        assertEquals("gpt-4o", step1[0].id)
        assertEquals("GPT-4o Original", step1[0].name)
        assertEquals("gpt-4o", step1[1].id)
        assertEquals("Custom", step1[1].name)

        // User continues typing "-mini" into index 1; index 0 must remain untouched
        val step2 = ModelConfigUiFormatters.updateModelAt(step1, 1) { it.copy(id = "gpt-4o-mini") }
        assertEquals("gpt-4o", step2[0].id)
        assertEquals("GPT-4o Original", step2[0].name)
        assertEquals("gpt-4o-mini", step2[1].id)
        assertEquals("Custom", step2[1].name)

        // Removing index 1 when two models share the same ID only removes index 1
        val afterRemove = ModelConfigUiFormatters.removeModelAt(step1, 1)
        assertEquals(1, afterRemove.size)
        assertEquals("GPT-4o Original", afterRemove[0].name)
    }

    @Test
    fun `validateModelsInProvider and validateProvidersConfig catch blank and duplicate model IDs`() {
        val validProvider = ProviderConfigItem(
            name = "openai-proxy",
            models = listOf(
                ModelConfigItem(id = "gpt-4o"),
                ModelConfigItem(id = "gpt-4o-mini")
            )
        )
        assertNull(ModelConfigUiFormatters.validateModelsInProvider(validProvider))
        assertNull(ModelConfigUiFormatters.validateProvidersConfig(listOf(validProvider)))

        val blankIdProvider = ProviderConfigItem(
            name = "openai-proxy",
            models = listOf(
                ModelConfigItem(id = "gpt-4o"),
                ModelConfigItem(id = "   ")
            )
        )
        val blankErr = ModelConfigUiFormatters.validateModelsInProvider(blankIdProvider)
        assertNotNull(blankErr)
        assertTrue(blankErr!!.contains("未填写标识"))

        val duplicateIdProvider = ProviderConfigItem(
            name = "openai-proxy",
            models = listOf(
                ModelConfigItem(id = "gpt-4o"),
                ModelConfigItem(id = "GPT-4O")
            )
        )
        val dupErr = ModelConfigUiFormatters.validateProvidersConfig(listOf(validProvider, duplicateIdProvider))
        assertNotNull(dupErr)
        assertTrue(dupErr!!.contains("重复的模型标识"))
    }

    @Test
    fun `formatCostEditText and sanitizeDecimalInput handle decimal editing cleanly`() {
        assertEquals("0", ModelConfigUiFormatters.formatCostEditText(0.0))
        assertEquals("0", ModelConfigUiFormatters.formatCostEditText(-1.0))
        assertEquals("3", ModelConfigUiFormatters.formatCostEditText(3.0))
        assertEquals("0.15", ModelConfigUiFormatters.formatCostEditText(0.15))
        assertEquals("0.000125", ModelConfigUiFormatters.formatCostEditText(0.000125))

        assertEquals("", ModelConfigUiFormatters.sanitizeDecimalInput(""))
        assertEquals("3.", ModelConfigUiFormatters.sanitizeDecimalInput("3."))
        assertEquals("0.05", ModelConfigUiFormatters.sanitizeDecimalInput("0.05"))
        assertEquals("1.25", ModelConfigUiFormatters.sanitizeDecimalInput("1.2.5abc"))
    }
}

