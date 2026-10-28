package com.pi.app.data.model

/** 模型目录负责补充名称和参数；既有会话的模型身份来自运行态或会话快照。 */
object SessionModelSelection {
    fun resolve(models: List<ModelInfo>, provider: String?, modelId: String?): ModelInfo? {
        if (provider.isNullOrBlank() || modelId.isNullOrBlank()) return null
        return models.firstOrNull {
            it.provider.equals(provider, ignoreCase = true) && it.id.equals(modelId, ignoreCase = true)
        } ?: ModelInfo(id = modelId, name = modelId, provider = provider)
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
            return models.firstOrNull {
                it.provider.equals(current.provider, ignoreCase = true) &&
                    it.id.equals(current.id, ignoreCase = true)
            } ?: current
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
