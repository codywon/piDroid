package com.pi.app.data.storage

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pidroid_prefs", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    var authToken: String
        get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value.trim()).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "My PC") ?: "My PC"
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    // 语音播报开关：默认关闭自动播报，支持手动按需点击播报
    var autoSpeakEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SPEAK, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SPEAK, value).apply()

    // 常驻通知栏保活开关：默认开启，确保连接网关与后台任务期间在系统通知栏常驻保活
    var persistentNotifyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERSISTENT_NOTIFY, true)
        set(value) = prefs.edit().putBoolean(KEY_PERSISTENT_NOTIFY, value).apply()

    // 马达触觉反馈开关：默认开启
    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS_ENABLED, value).apply()

    var defaultModelId: String
        get() = prefs.getString(KEY_DEFAULT_MODEL, "gpt-6.1-sol") ?: "gpt-6.1-sol"
        set(value) = prefs.edit().putString(KEY_DEFAULT_MODEL, value).apply()

    var defaultThinkingLevel: String
        get() = prefs.getString(KEY_DEFAULT_THINKING, "high") ?: "high"
        set(value) = prefs.edit().putString(KEY_DEFAULT_THINKING, value).apply()

    fun getSkillAlias(skillName: String): String? {
        return prefs.getString("alias_$skillName", null)
    }

    fun saveSkillAlias(skillName: String, alias: String) {
        prefs.edit().putString("alias_$skillName", alias).apply()
    }

    val isConfigured: Boolean
        get() = serverUrl.isNotEmpty() && authToken.isNotEmpty()

    fun saveConnection(url: String, token: String, name: String? = null) {
        prefs.edit().apply {
            putString(KEY_SERVER_URL, url.trimEnd('/'))
            putString(KEY_AUTH_TOKEN, token.trim())
            if (!name.isNullOrBlank()) {
                putString(KEY_DEVICE_NAME, name)
            }
            apply()
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_SERVER_URL = "key_server_url"
        private const val KEY_AUTH_TOKEN = "key_auth_token"
        private const val KEY_DEVICE_NAME = "key_device_name"
        private const val KEY_AUTO_SPEAK = "key_auto_speak"
        private const val KEY_PERSISTENT_NOTIFY = "key_persistent_notify"
        private const val KEY_HAPTICS_ENABLED = "key_haptics_enabled"
        private const val KEY_DEFAULT_MODEL = "key_default_model"
        private const val KEY_DEFAULT_THINKING = "key_default_thinking"
    }
}
