package com.pi.app.network

import com.google.gson.JsonObject

/**
 * 让「经中转站暴露的 DeepSeek 模型」也能通过思考模式的回传校验（纯 JVM，可单测）。
 *
 * ## 为什么需要
 * DeepSeek 在思考模式下要求：每一轮历史里的 assistant 消息都必须把 `reasoning_content`
 * 原样带回去，**字段缺失就是 400**：
 * `The reasoning_content in the thinking mode must be passed back to the API.`
 *
 * pi-ai 自己会补这个字段，但它的判定只认两种身份（`dist/api/openai-completions.js`）：
 * ```js
 * const isDeepSeek = provider === "deepseek" || baseUrl.toLowerCase().includes("deepseek.com");
 * ```
 * 于是 `codywon/deepseek-flash`（baseUrl 为某个中转站域名）这种组合被判成「不是 DeepSeek」，
 * 重放历史时不写该字段 —— 用户看到的就是连续 400、点重试还是 400（历史没变）。
 * 官方 `deepseek` provider 不受影响：models-store 抓下来的模型条目本来就带
 * `requiresReasoningContentOnAssistantMessages: true`。
 *
 * ## 这里做什么
 * 在**写盘前**（[ModelsConfigWriteContract.payloadOf]，models.json 的唯一出口）给命中
 * DeepSeek 的自定义模型条目补上这一个 compat 标志。只补这一个键：
 * - `thinkingFormat` / `maxTokensField` 是更激进的协议改写，中转站是否接受未知字段不可知，不碰；
 * - 已经显式写过该键（哪怕写成 `false`）一律尊重，不覆盖。
 *
 * 与 pi-ai 的语义保持一致：只有当条目 `reasoning == true` 时补，因为 pi-ai 注入空字段的前置
 * 条件就是 `model.reasoning`，非思考模型补了也只是死数据。
 */
internal object DeepSeekReasoningCompat {

    /** pi-ai 的 `ProviderCompatSchema` 里的字段名，拼错就等于没写。 */
    const val FLAG = "requiresReasoningContentOnAssistantMessages"

    private const val PROVIDER_DEEPSEEK = "deepseek"
    private const val BASE_URL_MARKER = "deepseek.com"
    private const val MODEL_ID_MARKER = "deepseek"

    /**
     * 原地给 `providers.<key>.models[]` 里命中的条目补 compat，返回被补过的 `provider/modelId`。
     *
     * 只处理自定义提供商的 `models` 数组：内置提供商的模型来自 models-store.json（已带该标志），
     * 而 `modelOverrides` 是「覆盖内置模型参数」，不去改写它，免得把一个纯参数覆盖表变成协议声明。
     */
    fun heal(config: JsonObject): List<String> {
        val providers = config.get("providers")
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return emptyList()
        val patched = mutableListOf<String>()

        for ((providerKey, providerElement) in providers.entrySet()) {
            if (!providerElement.isJsonObject) continue
            val provider = providerElement.asJsonObject
            val baseUrl = provider.stringOrNull("baseUrl").orEmpty()
            val models = provider.get("models")
                ?.takeIf { it.isJsonArray }?.asJsonArray
                ?: continue

            for (modelElement in models) {
                if (!modelElement.isJsonObject) continue
                val model = modelElement.asJsonObject
                val modelId = model.stringOrNull("id") ?: continue
                if (!isDeepSeekTarget(providerKey, modelId, baseUrl)) continue
                if (model.get("reasoning")?.takeIf { !it.isJsonNull }?.asBoolean != true) continue

                val compat = model.get("compat")
                    ?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: JsonObject().also { model.add("compat", it) }
                if (compat.has(FLAG)) continue

                compat.addProperty(FLAG, true)
                patched += "$providerKey/$modelId"
            }
        }
        return patched
    }

    /**
     * 命中判定：provider 名是 deepseek、baseUrl 指向 deepseek 官方域名，或**模型 id 自带 deepseek**。
     *
     * 最后一条是这次真正救命的那条 —— 中转站的 provider 名和域名都不带 deepseek，
     * 但 `deepseek-flash` / `deepseek-v4-pro` 这样的 id 一眼就是 DeepSeek 家族。
     */
    fun isDeepSeekTarget(providerKey: String?, modelId: String?, baseUrl: String?): Boolean {
        val provider = providerKey.orEmpty().trim().lowercase()
        val model = modelId.orEmpty().trim().lowercase()
        val url = baseUrl.orEmpty().trim().lowercase()
        return provider == PROVIDER_DEEPSEEK ||
            url.contains(BASE_URL_MARKER) ||
            model.contains(MODEL_ID_MARKER)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
}
