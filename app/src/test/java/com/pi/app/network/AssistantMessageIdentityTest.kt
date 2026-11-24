package com.pi.app.network

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantMessageIdentityTest {
    @Test
    fun `provider response id arriving after start does not change message identity`() {
        val message = JsonParser.parseString("""{"timestamp":1763000000000} """).asJsonObject
        val started = assistantMessageIdentity(message)
        message.addProperty("responseId", "chatcmpl-deepseek-1")
        assertEquals("1763000000000", started)
        assertEquals(started, assistantMessageIdentity(message))
        message.addProperty("id", "provider-final-id")
        assertEquals(started, assistantMessageIdentity(message))
    }

    @Test
    fun `legacy messages use available identity and ignore null or structured fields`() {
        assertEquals("legacy", assistantMessageIdentity(JsonParser.parseString(
            """{"timestamp":null,"id":"legacy","responseId":"response"}"""
        ).asJsonObject))
        assertEquals("response", assistantMessageIdentity(JsonParser.parseString(
            """{"timestamp":{},"id":" ","responseId":"response"}"""
        ).asJsonObject))
        assertNull(assistantMessageIdentity(JsonParser.parseString("{}").asJsonObject))
    }
}
