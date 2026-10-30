package com.pi.app.data.model

/**
 * models.json / 本地覆盖 与 `/api/models` 目录合并时的定位规则。
 *
 * 模型身份是 (provider, modelId) 一对，而合并时最大的坑是「同名不同供应商」：
 * `deepseek-flash` 这类 id 常常同时在 `codywon` 和 `deepseek` 两个 provider 下各有一份。
 * 早前这三处合并都写成 `精确命中 ?: 按 id 兜底`，于是给 B 供应商的覆盖参数会落到 A 供应商的
 * 同名模型上（甚至把 `provider` 字段一起改写成 B）—— 用户看到的就是「同样模型会串提供商」。
 */
object ModelCatalogMerge {
    /**
     * 在目录里定位覆盖目标。
     *
     * - 精确命中 `provider:id` 时直接返回；
     * - 否则仅当该 id 在整份目录里**唯一**时才允许跨 provider 回退（models.json 里用了别名/大小写
     *   不同的 provider 名，或凭据还没配置导致 `provider` 未被列出的情形）；
     * - id 在多个 provider 下重名时返回 -1，宁可这条覆盖暂不生效，也不能把配置写到别人的同名模型上。
     */
    fun indexOf(models: List<ModelInfo>, provider: String, modelId: String): Int {
        val exact = models.indexOfFirst {
            it.provider.equals(provider, ignoreCase = true) && it.id.equals(modelId, ignoreCase = true)
        }
        if (exact >= 0) return exact

        val sameId = models.indices.filter { models[it].id.equals(modelId, ignoreCase = true) }
        return if (sameId.size == 1) sameId.first() else -1
    }
}
