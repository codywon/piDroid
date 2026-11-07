package com.pi.app.ui.settings

import com.pi.app.data.model.ModelConfigItem
import com.pi.app.data.model.ProviderConfigItem
import com.pi.app.data.model.ThinkingLevel
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 模型管理工作台的纯逻辑格式化与预设辅助函数（与 Android / Compose 运行时解耦，供单元测试直接验证）。
 */
object ModelConfigUiFormatters {

    /** 上下文窗口快捷预设胶囊：32k / 128k / 200k / 1M */
    val CONTEXT_WINDOW_PRESETS: List<Pair<String, Long>> = listOf(
        "32k" to 32_000L,
        "128k" to 128_000L,
        "200k" to 200_000L,
        "1M" to 1_000_000L
    )

    /** 最大输出快捷预设胶囊：8k / 16k / 64k / 128k */
    val MAX_OUTPUT_PRESETS: List<Pair<String, Long>> = listOf(
        "8k" to 8_192L,
        "16k" to 16_384L,
        "64k" to 65_536L,
        "128k" to 131_072L
    )

    private val BINARY_K_MULTIPLIERS = setOf(1L, 2L, 4L, 8L, 16L, 32L, 64L, 128L, 256L, 512L)

    /**
     * 全局默认思考深度卡片顶栏右侧的档位与预算标签（如 `high · 16k 预算` 或 `off · 关闭思考`）。
     */
    fun formatThinkingBudgetLabel(level: ThinkingLevel): String {
        val budgetText = when (level) {
            ThinkingLevel.OFF -> "关闭思考"
            ThinkingLevel.MINIMAL -> "512 预算"
            ThinkingLevel.LOW -> "1k 预算"
            ThinkingLevel.MEDIUM -> "4k 预算"
            ThinkingLevel.HIGH -> "16k 预算"
            ThinkingLevel.XHIGH -> "32k 预算"
            ThinkingLevel.MAX -> "64k 预算"
        }
        return "${level.value} · $budgetText"
    }

    /**
     * 将上下文窗口或最大输出 token 数值换算为紧凑规格文案（如 `200k`、`1M`、`16k`）。
     * 同时兼容 1000 进制（如 128000 -> 128k、200000 -> 200k、1000000 -> 1M）
     * 与 1024 进制常见规格（如 4096 -> 4k、8192 -> 8k、16384 -> 16k、32768 -> 32k、65536 -> 64k）。
     */
    fun formatModelTokenSpec(tokens: Long): String {
        if (tokens <= 0L) return "—"
        if (tokens >= 1_000_000L) {
            if (tokens % 1_048_576L == 0L) {
                return "${tokens / 1_048_576L}M"
            }
            if (tokens % 1_000_000L == 0L) {
                return "${tokens / 1_000_000L}M"
            }
            val millions = tokens / 1_000_000.0
            val formatted = String.format(Locale.US, "%.1f", millions).removeSuffix(".0")
            return "${formatted}M"
        }
        if (tokens >= 1_000L) {
            if (tokens % 1_024L == 0L && (tokens / 1_024L) in BINARY_K_MULTIPLIERS) {
                return "${tokens / 1_024L}k"
            }
            if (tokens % 1_000L == 0L) {
                return "${tokens / 1_000L}k"
            }
            return "${(tokens / 1_000.0).roundToLong()}k"
        }
        return tokens.toString()
    }

    /**
     * 判断当前输入的 token 数是否命中某个快捷预设胶囊（精确相等或换算后同规格名均视为命中）。
     */
    fun isPresetSelected(currentTokens: Long, presetTokens: Long, presetLabel: String): Boolean {
        if (currentTokens <= 0L) return false
        return currentTokens == presetTokens || formatModelTokenSpec(currentTokens).equals(presetLabel, ignoreCase = true)
    }

    /**
     * 模型清单行第二行摘要：展示模型显示名（若与 ID 不同且非空）与 `上下文 200k / 输出 16k` 规格摘要。
     */
    fun formatModelRowSummary(model: ModelConfigItem): String {
        val ctxSpec = formatModelTokenSpec(model.contextWindow)
        val outSpec = formatModelTokenSpec(model.maxTokens)
        val specPart = "上下文 $ctxSpec / 输出 $outSpec"
        val cleanName = model.name.trim()
        return if (cleanName.isNotEmpty() && !cleanName.equals(model.id.trim(), ignoreCase = true)) {
            "$cleanName · $specPart"
        } else {
            specPart
        }
    }

    /**
     * 生成当前提供商下不冲突的新增模型默认 ID（`custom-model`, `custom-model-1`, ...）。
     */
    fun nextCustomModelId(existingModels: List<ModelConfigItem>): String {
        var candidate = "custom-model"
        var index = 1
        while (existingModels.any { it.id.equals(candidate, ignoreCase = true) }) {
            candidate = "custom-model-$index"
            index++
        }
        return candidate
    }

    /**
     * 按精确下标更新单个模型，避免修改模型 ID 途经已有同名 ID 时污染列表中的其他模型。
     */
    fun updateModelAt(
        models: List<ModelConfigItem>,
        index: Int,
        transform: (ModelConfigItem) -> ModelConfigItem
    ): List<ModelConfigItem> {
        if (index !in models.indices) return models
        return models.mapIndexed { i, item -> if (i == index) transform(item) else item }
    }

    /**
     * 按精确下标移除单个模型，避免同名模型被一并误删。
     */
    fun removeModelAt(models: List<ModelConfigItem>, index: Int): List<ModelConfigItem> {
        if (index !in models.indices) return models
        return models.filterIndexed { i, _ -> i != index }
    }

    /**
     * 校验单个提供商下的模型列表合法性（ID 不可为空、同一提供商下 ID 不可重复）。
     *
     * @return 若合法返回 `null`；若不合法返回中文错误提示。
     */
    fun validateModelsInProvider(provider: ProviderConfigItem): String? {
        val seenIds = mutableSetOf<String>()
        for (model in provider.models) {
            val trimmedId = model.id.trim()
            if (trimmedId.isEmpty()) {
                return "提供商 ${provider.name} 中存在未填写标识 (ID) 的模型"
            }
            val lowerKey = trimmedId.lowercase(Locale.ROOT)
            if (!seenIds.add(lowerKey)) {
                return "提供商 ${provider.name} 中存在重复的模型标识: $trimmedId"
            }
        }
        return null
    }

    /**
     * 校验全部提供商的模型配置合法性（保存前拦截空 ID 静默丢失或重复 ID 覆盖）。
     */
    fun validateProvidersConfig(providers: List<ProviderConfigItem>): String? {
        for (provider in providers) {
            val err = validateModelsInProvider(provider)
            if (err != null) return err
        }
        return null
    }

    /**
     * 将 Double 价格格式化为适合输入框编辑的无冗余后缀文本（如 `0.0` -> `"0"`, `3.0` -> `"3"`, `0.15` -> `"0.15"`）。
     */
    fun formatCostEditText(cost: Double): String {
        if (cost <= 0.0 || cost.isNaN() || cost.isInfinite()) return "0"
        return BigDecimal.valueOf(cost).stripTrailingZeros().toPlainString()
    }

    /**
     * 清洗小数价格输入框文本：仅保留数字与最多一个小数点，保留输入中途的 `""`、`"3."`、`"0.0"` 等合法形态。
     */
    fun sanitizeDecimalInput(raw: String): String {
        val sb = StringBuilder(raw.length)
        var hasDot = false
        for (ch in raw) {
            if (ch.isDigit()) {
                sb.append(ch)
            } else if (ch == '.' && !hasDot) {
                hasDot = true
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
