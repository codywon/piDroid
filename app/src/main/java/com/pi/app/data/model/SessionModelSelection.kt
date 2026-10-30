package com.pi.app.data.model

/**
 * 模型目录负责补充名称和参数；既有会话的模型身份来自运行态或会话快照。
 *
 * 模型身份 = **(provider, modelId) 一对**，与 pi-web 完全一致：
 * - 选择器判定「这一行是不是当前模型」用的是 `e.modelId===m.modelId && e.provider===m.provider`
 *   （`app/page-*.js` 里 model-selector 的 `active`），默认模型星标同理；
 * - 切换走 `POST /api/agent/{id} {"type":"set_model","provider":…,"modelId":…}`，也带 provider。
 *
 * 一个 modelId 可以在不同 provider 下各有一份（例如 codywon/deepseek-flash 与 deepseek/deepseek-flash），
 * 所以任何「是不是同一个模型」的判断都不能只比 id，否则两行会同时被标成当前/默认，
 * 切了供应商也看不出来——表现就是「同样模型会串提供商」。
 */
object SessionModelSelection {
    /** 严格身份判定：provider 与 modelId 都必须对上。 */
    fun matches(model: ModelInfo, provider: String?, modelId: String?): Boolean {
        if (provider.isNullOrBlank() || modelId.isNullOrBlank()) return false
        return model.provider.equals(provider, ignoreCase = true) && model.id.equals(modelId, ignoreCase = true)
    }

    /**
     * 选择器行「是否当前会话模型」。
     *
     * 与 pi-web 的 `active` 同口径：id 必须一致；provider 已知时还必须一致。
     * 只有 provider 未知（会话从未上报过模型元数据）时才退回按 id 判定——
     * 此时没有任何可用于消歧的信息，按 id 高亮是唯一能给出提示的做法。
     */
    fun isSelected(model: ModelInfo, selectedProvider: String?, selectedModelId: String?): Boolean {
        if (selectedModelId.isNullOrBlank() || !model.id.equals(selectedModelId, ignoreCase = true)) return false
        return selectedProvider.isNullOrBlank() || model.provider.equals(selectedProvider, ignoreCase = true)
    }

    /** 该 modelId 是否同时挂在多个 provider 下（此时不写 provider 就说不清是谁）。 */
    fun isProviderAmbiguous(models: List<ModelInfo>, modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return false
        return models.asSequence()
            .filter { it.id.equals(modelId, ignoreCase = true) }
            .map { it.provider.lowercase() }
            .distinct()
            .take(2)
            .count() > 1
    }

    fun resolve(models: List<ModelInfo>, provider: String?, modelId: String?): ModelInfo? {
        if (provider.isNullOrBlank() || modelId.isNullOrBlank()) return null
        return models.firstOrNull { matches(it, provider, modelId) }
            ?: ModelInfo(id = modelId, name = modelId, provider = provider)
    }

    fun afterCatalogRefresh(
        models: List<ModelInfo>,
        current: ModelInfo,
        hasOpenSession: Boolean,
        defaultProvider: String,
        defaultModelId: String
    ): ModelInfo {
        if (hasOpenSession) {
            // 目录刷新不得把全局默认值或另一个 provider 的同名模型写入当前会话。
            return models.firstOrNull { matches(it, current.provider, current.id) } ?: current
        }
        return resolve(models, defaultProvider, defaultModelId) ?: models.firstOrNull() ?: current
    }

    fun fromSnapshot(
        models: List<ModelInfo>,
        runtime: AgentRuntimeState?,
        historyProvider: String?,
        historyModelId: String?
    ): ModelInfo? {
        // 空闲但仍存活的 runtime 也有权威模型；running 只表示是否正在执行任务。
        val live = runtime?.takeIf { it.runtimeAlive }
        return resolve(models, live?.provider, live?.modelId)
            ?: resolve(models, historyProvider, historyModelId)
    }
}
