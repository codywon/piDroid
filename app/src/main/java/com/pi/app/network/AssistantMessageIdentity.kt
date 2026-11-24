package com.pi.app.network

import com.google.gson.JsonObject

/** pi 在 start 前固定 timestamp，responseId 则在首个供应商响应到达后才补上。 */
internal fun assistantMessageIdentity(message: JsonObject): String? {
    for (key in listOf("timestamp", "id", "messageId", "responseId")) {
        val value = message.get(key)
            ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
            ?.asString?.trim()
        if (!value.isNullOrEmpty()) return value
    }
    return null
}
