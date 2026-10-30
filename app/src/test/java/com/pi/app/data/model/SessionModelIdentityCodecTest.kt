package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionModelIdentityCodecTest {
    @Test
    fun `provider and model id survive a round trip`() {
        val encoded = SessionModelIdentityCodec.encode("deepseek", "deepseek-flash")
        assertEquals("deepseek/deepseek-flash", encoded)
        assertEquals("deepseek" to "deepseek-flash", SessionModelIdentityCodec.decode(encoded))
    }

    @Test
    fun `model id containing a slash keeps its whole tail`() {
        val encoded = SessionModelIdentityCodec.encode("codywon", "qwen/qwen3.8-flash")
        assertEquals("codywon" to "qwen/qwen3.8-flash", SessionModelIdentityCodec.decode(encoded))
    }

    @Test
    fun `garbage decodes to null instead of a half identity`() {
        assertNull(SessionModelIdentityCodec.decode(null))
        assertNull(SessionModelIdentityCodec.decode(""))
        assertNull(SessionModelIdentityCodec.decode("deepseek"))
        assertNull(SessionModelIdentityCodec.decode("/deepseek-flash"))
        assertNull(SessionModelIdentityCodec.decode("deepseek/"))
    }

    @Test
    fun `same model id under two providers stays two separate session identities`() {
        var store: Map<String, String> = emptyMap()
        store = SessionModelIdentityCodec.withEntry(store, "s1", "deepseek", "deepseek-flash")
        store = SessionModelIdentityCodec.withEntry(store, "s2", "codywon", "deepseek-flash")
        assertEquals("deepseek" to "deepseek-flash", SessionModelIdentityCodec.decode(store["s1"]))
        assertEquals("codywon" to "deepseek-flash", SessionModelIdentityCodec.decode(store["s2"]))
    }

    @Test
    fun `rewriting a session replaces the old identity`() {
        var store = SessionModelIdentityCodec.withEntry(emptyMap(), "s1", "codywon", "deepseek-flash")
        store = SessionModelIdentityCodec.withEntry(store, "s1", "deepseek", "deepseek-flash")
        assertEquals(1, store.size)
        assertEquals("deepseek" to "deepseek-flash", SessionModelIdentityCodec.decode(store["s1"]))
    }

    @Test
    fun `oldest entries are dropped past the cap`() {
        var store: Map<String, String> = emptyMap()
        repeat(5) { i -> store = SessionModelIdentityCodec.withEntry(store, "s$i", "codywon", "m$i", limit = 3) }
        assertEquals(3, store.size)
        assertEquals(setOf("s2", "s3", "s4"), store.keys)
    }

    @Test
    fun `blank parts never create an identity`() {
        val store = SessionModelIdentityCodec.withEntry(emptyMap(), "s1", "", "deepseek-flash")
        assertEquals(0, store.size)
        assertEquals(0, SessionModelIdentityCodec.withEntry(emptyMap(), " ", "deepseek", "x").size)
    }
}
