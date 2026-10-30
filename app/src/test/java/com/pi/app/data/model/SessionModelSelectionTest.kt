package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SessionModelSelectionTest {
    private val gpt = ModelInfo("gpt-6.1-sol", "GPT-6.1 Sol", "codywon")
    private val deepseek = ModelInfo("deepseek-flash", "DeepSeek Flash", "codywon")
    private val models = listOf(deepseek, gpt)

    private fun refresh(current: ModelInfo, sessionOpen: Boolean, catalog: List<ModelInfo> = models) =
        SessionModelSelection.afterCatalogRefresh(catalog, current, sessionOpen, deepseek.provider, deepseek.id)

    @Test
    fun `late catalog response cannot replace GPT session with global DeepSeek default`() {
        val snapshot = SessionModelSelection.fromSnapshot(models, null, gpt.provider, gpt.id)!!
        assertSame(gpt, refresh(snapshot, sessionOpen = true))
    }

    @Test
    fun `catalog and session response order produce the same selected model`() {
        val catalogFirst = refresh(deepseek, sessionOpen = false)
        val sessionAfter = SessionModelSelection.fromSnapshot(models, null, gpt.provider, gpt.id)!!
        assertSame(deepseek, catalogFirst)
        assertEquals(sessionAfter, refresh(sessionAfter, sessionOpen = true))
    }

    @Test
    fun `live GPT model beats stale DeepSeek history even when runtime is idle`() {
        val runtime = AgentRuntimeState(
            provider = gpt.provider, modelId = gpt.id, runtimeAlive = true, running = false
        )
        assertSame(gpt, SessionModelSelection.fromSnapshot(models, runtime, deepseek.provider, deepseek.id))
    }

    @Test
    fun `subsequent snapshot follows model changed on PC`() {
        val before = AgentRuntimeState(provider = gpt.provider, modelId = gpt.id, runtimeAlive = true)
        val after = before.copy(modelId = deepseek.id)
        assertSame(gpt, SessionModelSelection.fromSnapshot(models, before, gpt.provider, gpt.id))
        assertSame(deepseek, SessionModelSelection.fromSnapshot(models, after, gpt.provider, gpt.id))
    }

    @Test
    fun `session history is used when runtime is unavailable`() {
        assertSame(gpt, SessionModelSelection.fromSnapshot(models, null, gpt.provider, gpt.id))
        val stopped = AgentRuntimeState(provider = deepseek.provider, modelId = deepseek.id)
        assertSame(gpt, SessionModelSelection.fromSnapshot(models, stopped, gpt.provider, gpt.id))
    }

    @Test
    fun `runtime can supply model when history request failed`() {
        val runtime = AgentRuntimeState(provider = gpt.provider, modelId = gpt.id, runtimeAlive = true)
        assertSame(gpt, SessionModelSelection.fromSnapshot(models, runtime, null, null))
    }

    @Test
    fun `missing catalog entry preserves runtime identity then gains catalog metadata`() {
        val runtime = AgentRuntimeState(provider = gpt.provider, modelId = gpt.id, runtimeAlive = true)
        val selected = SessionModelSelection.fromSnapshot(listOf(deepseek), runtime, deepseek.provider, deepseek.id)!!
        assertEquals(gpt.id, selected.id)
        assertEquals(gpt.provider, selected.provider)
        assertSame(selected, refresh(selected, sessionOpen = true, catalog = listOf(deepseek)))
        assertSame(gpt, refresh(selected, sessionOpen = true))
    }

    @Test
    fun `same model id under another provider cannot replace session provider`() {
        val other = gpt.copy(provider = "other", name = "Other GPT")
        val selected = SessionModelSelection.fromSnapshot(listOf(other), null, gpt.provider, gpt.id)!!
        assertEquals(gpt.provider, selected.provider)
        assertSame(selected, refresh(selected, sessionOpen = true, catalog = listOf(other)))
    }

    @Test
    fun `catalog refresh enriches current session model without changing identity`() {
        val cached = ModelInfo(gpt.id, gpt.id, gpt.provider)
        assertSame(gpt, refresh(cached, sessionOpen = true))
    }

    @Test
    fun `global default applies when no session is open`() {
        assertSame(deepseek, refresh(gpt, sessionOpen = false))
    }

    @Test
    fun `missing model metadata does not fabricate GPT`() {
        assertNull(SessionModelSelection.fromSnapshot(models, null, "", ""))
        assertNull(SessionModelSelection.resolve(models, null, gpt.id))
    }

    // ── 同名不同供应商：切了供应商必须能看出切了哪一行 ──

    private val deepseekOtherProvider = ModelInfo("deepseek-flash", "DeepSeek Flash", "deepseek")

    @Test
    fun `only the row of the selected provider is marked as current`() {
        val catalog = listOf(deepseek, deepseekOtherProvider, gpt)
        val selected = SessionModelSelection.isSelected(deepseekOtherProvider, "deepseek", "deepseek-flash")
        val stale = SessionModelSelection.isSelected(deepseek, "deepseek", "deepseek-flash")
        assertEquals(true, selected)
        assertEquals(false, stale)
        assertEquals(1, catalog.count { SessionModelSelection.isSelected(it, "deepseek", "deepseek-flash") })
    }

    @Test
    fun `unknown provider falls back to model id so something is still highlighted`() {
        assertEquals(true, SessionModelSelection.isSelected(deepseek, null, "deepseek-flash"))
        assertEquals(false, SessionModelSelection.isSelected(deepseek, "deepseek", "deepseek-flash"))
        assertEquals(false, SessionModelSelection.isSelected(deepseek, "codywon", "gpt-6-sol"))
    }

    @Test
    fun `same id under two providers is flagged as ambiguous and single provider is not`() {
        val catalog = listOf(deepseek, deepseekOtherProvider, gpt)
        assertEquals(true, SessionModelSelection.isProviderAmbiguous(catalog, "deepseek-flash"))
        assertEquals(false, SessionModelSelection.isProviderAmbiguous(catalog, "gpt-6.1-sol"))
        assertEquals(false, SessionModelSelection.isProviderAmbiguous(catalog, null))
    }

    @Test
    fun `strict identity match keeps provider apart`() {
        assertEquals(true, SessionModelSelection.matches(deepseekOtherProvider, "deepseek", "deepseek-flash"))
        assertEquals(false, SessionModelSelection.matches(deepseek, "deepseek", "deepseek-flash"))
        assertEquals(false, SessionModelSelection.matches(deepseek, null, "deepseek-flash"))
        assertSame(deepseekOtherProvider, SessionModelSelection.resolve(listOf(deepseek, deepseekOtherProvider), "deepseek", "deepseek-flash"))
    }

    @Test
    fun `switching provider keeps the new provider after the next snapshot`() {
        val catalog = listOf(deepseek, deepseekOtherProvider)
        val switched = SessionModelSelection.resolve(catalog, "deepseek", "deepseek-flash")!!
        assertEquals("deepseek", switched.provider)
        val runtime = AgentRuntimeState(provider = "deepseek", modelId = "deepseek-flash", runtimeAlive = true)
        assertSame(deepseekOtherProvider, SessionModelSelection.fromSnapshot(catalog, runtime, "codywon", "deepseek-flash"))
    }
}
