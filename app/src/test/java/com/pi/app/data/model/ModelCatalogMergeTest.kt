package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogMergeTest {
    private val codywonFlash = ModelInfo("deepseek-flash", "DeepSeek Flash", "codywon")
    private val deepseekFlash = ModelInfo("deepseek-flash", "DeepSeek Flash", "deepseek")
    private val gpt = ModelInfo("gpt-6.1-sol", "GPT-6.1 Sol", "codywon")

    @Test
    fun `override hits the exact provider even when another provider has the same id`() {
        val catalog = listOf(codywonFlash, deepseekFlash, gpt)
        assertEquals(1, ModelCatalogMerge.indexOf(catalog, "deepseek", "deepseek-flash"))
        assertEquals(0, ModelCatalogMerge.indexOf(catalog, "codywon", "deepseek-flash"))
    }

    @Test
    fun `provider comparison is case insensitive`() {
        val catalog = listOf(codywonFlash, deepseekFlash)
        assertEquals(1, ModelCatalogMerge.indexOf(catalog, "DeepSeek", "DeepSeek-Flash"))
    }

    @Test
    fun `ambiguous id never falls back to another provider`() {
        val catalog = listOf(codywonFlash, deepseekFlash)
        // provider 未出现在目录里，但该 id 有两个来源 —— 宁可不动，也不要把配置写到别人身上
        assertEquals(-1, ModelCatalogMerge.indexOf(catalog, "other", "deepseek-flash"))
    }

    @Test
    fun `unique id still falls back across provider aliases`() {
        val catalog = listOf(gpt)
        assertEquals(0, ModelCatalogMerge.indexOf(catalog, "codywon-alias", "gpt-6.1-sol"))
        assertEquals(-1, ModelCatalogMerge.indexOf(catalog, "codywon", "gemini-3.8-flash"))
    }
}
