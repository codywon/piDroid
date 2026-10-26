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

    // 任务完成提示音开关：默认开启 (对标 pi-web)
    var completionSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMPLETION_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_COMPLETION_SOUND_ENABLED, value).apply()

    var defaultModelId: String
        get() = prefs.getString(KEY_DEFAULT_MODEL, "gpt-6.1-sol") ?: "gpt-6.1-sol"
        set(value) = prefs.edit().putString(KEY_DEFAULT_MODEL, value).apply()

    var defaultThinkingLevel: String
        get() = prefs.getString(KEY_DEFAULT_THINKING, "high") ?: "high"
        set(value) = prefs.edit().putString(KEY_DEFAULT_THINKING, value).apply()

    var defaultToolPreset: String
        get() = prefs.getString(KEY_DEFAULT_TOOL_PRESET, "full") ?: "full"
        set(value) = prefs.edit().putString(KEY_DEFAULT_TOOL_PRESET, value).apply()

    fun getSkillAlias(skillName: String): String? {
        return prefs.getString("alias_$skillName", null)
    }

    fun saveSkillAlias(skillName: String, alias: String) {
        prefs.edit().putString("alias_$skillName", alias).apply()
    }

    fun getSkillContentOverride(skillName: String): String? {
        return prefs.getString("skill_md_$skillName", null)?.takeIf { it.isNotBlank() }
    }

    fun saveSkillContentOverride(skillName: String, content: String) {
        prefs.edit().putString("skill_md_$skillName", content).apply()
    }

    // 会话置顶管理
    fun getPinnedSessionIds(): Set<String> {
        return prefs.getStringSet(KEY_PINNED_SESSIONS, emptySet()) ?: emptySet()
    }

    fun isSessionPinned(sessionId: String): Boolean {
        return getPinnedSessionIds().contains(sessionId)
    }

    fun togglePinSession(sessionId: String): Boolean {
        val current = getPinnedSessionIds().toMutableSet()
        val willPin = !current.contains(sessionId)
        if (willPin) {
            current.add(sessionId)
        } else {
            current.remove(sessionId)
        }
        prefs.edit().putStringSet(KEY_PINNED_SESSIONS, current).apply()
        return willPin
    }

    // 会话归档管理 (对标 pi-web 侧边栏底部已归档体系)
    fun getArchivedSessionIds(): Set<String> {
        return prefs.getStringSet(KEY_ARCHIVED_SESSIONS, emptySet()) ?: emptySet()
    }

    fun isSessionArchived(sessionId: String): Boolean {
        return getArchivedSessionIds().contains(sessionId)
    }

    fun toggleArchiveSession(sessionId: String): Boolean {
        val current = getArchivedSessionIds().toMutableSet()
        val willArchive = !current.contains(sessionId)
        if (willArchive) {
            current.add(sessionId)
        } else {
            current.remove(sessionId)
        }
        prefs.edit().putStringSet(KEY_ARCHIVED_SESSIONS, current).apply()
        return willArchive
    }

    fun removeSessionLocalData(sessionId: String) {
        val currentPinned = getPinnedSessionIds().toMutableSet()
        currentPinned.remove(sessionId)
        val currentArchived = getArchivedSessionIds().toMutableSet()
        currentArchived.remove(sessionId)
        prefs.edit()
            .putStringSet(KEY_PINNED_SESSIONS, currentPinned)
            .putStringSet(KEY_ARCHIVED_SESSIONS, currentArchived)
            .remove("session_title_$sessionId")
            .apply()
    }

    // 会话自定义与自动生成标题
    fun getSessionCustomTitle(sessionId: String): String? {
        return prefs.getString("session_title_$sessionId", null)
    }

    fun saveSessionCustomTitle(sessionId: String, title: String) {
        prefs.edit().putString("session_title_$sessionId", title.trim()).apply()
    }

    // 自定义工作区项目目录列表（支持在手机端直接指定或远程创建 PC 项目目录）
    fun getCustomWorkspaces(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_WORKSPACES, emptySet()) ?: emptySet()
    }

    fun addCustomWorkspace(path: String) {
        val clean = path.trim()
        if (clean.isBlank()) return
        val current = getCustomWorkspaces().toMutableSet()
        current.add(clean)
        prefs.edit().putStringSet(KEY_CUSTOM_WORKSPACES, current).apply()
    }

    fun removeCustomWorkspace(path: String) {
        val current = getCustomWorkspaces().toMutableSet()
        current.remove(path.trim())
        prefs.edit().putStringSet(KEY_CUSTOM_WORKSPACES, current).apply()
    }

    // 模型自定义参数覆盖持久化（解决修改内置/网关模型 contextWindow、maxTokens 等参数后重新打开被默认目录覆盖的问题）
    data class StoredModelOverride(
        val provider: String,
        val modelId: String,
        val name: String,
        val baseUrl: String,
        val apiType: String,
        val contextWindow: Long,
        val maxTokens: Long,
        val reasoning: Boolean,
        val supportsVision: Boolean
    )

    fun saveModelOverride(override: StoredModelOverride) {
        val raw = prefs.getString(KEY_MODEL_OVERRIDES, "{}") ?: "{}"
        val root = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: com.google.gson.JsonObject()
        val key = "${override.provider.trim()}:${override.modelId.trim()}"
        val obj = com.google.gson.JsonObject().apply {
            addProperty("provider", override.provider.trim())
            addProperty("modelId", override.modelId.trim())
            addProperty("name", override.name.trim())
            addProperty("baseUrl", override.baseUrl.trim())
            addProperty("apiType", override.apiType.trim())
            addProperty("contextWindow", override.contextWindow)
            addProperty("maxTokens", override.maxTokens)
            addProperty("reasoning", override.reasoning)
            addProperty("supportsVision", override.supportsVision)
        }
        root.add(key, obj)
        prefs.edit().putString(KEY_MODEL_OVERRIDES, root.toString()).apply()
    }

    fun removeModelOverride(provider: String, modelId: String) {
        val raw = prefs.getString(KEY_MODEL_OVERRIDES, "{}") ?: "{}"
        val root = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return
        root.remove("${provider.trim()}:${modelId.trim()}")
        prefs.edit().putString(KEY_MODEL_OVERRIDES, root.toString()).apply()
    }

    fun getModelOverrides(): Map<String, StoredModelOverride> {
        val raw = prefs.getString(KEY_MODEL_OVERRIDES, "{}") ?: "{}"
        val root = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return emptyMap()
        val map = mutableMapOf<String, StoredModelOverride>()
        for ((k, v) in root.entrySet()) {
            if (!v.isJsonObject) continue
            val o = v.asJsonObject
            val provider = o.get("provider")?.asString ?: continue
            val modelId = o.get("modelId")?.asString ?: continue
            map[k] = StoredModelOverride(
                provider = provider,
                modelId = modelId,
                name = o.get("name")?.asString ?: modelId,
                baseUrl = o.get("baseUrl")?.asString ?: "",
                apiType = o.get("apiType")?.asString ?: "openai-completions",
                contextWindow = o.get("contextWindow")?.asLong ?: 200000L,
                maxTokens = o.get("maxTokens")?.asLong ?: 8192L,
                reasoning = o.get("reasoning")?.asBoolean ?: true,
                supportsVision = o.get("supportsVision")?.asBoolean ?: false
            )
        }
        return map
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
        private const val KEY_COMPLETION_SOUND_ENABLED = "key_completion_sound_enabled"
        private const val KEY_DEFAULT_MODEL = "key_default_model"
        private const val KEY_DEFAULT_THINKING = "key_default_thinking"
        private const val KEY_DEFAULT_TOOL_PRESET = "key_default_tool_preset"
        private const val KEY_PINNED_SESSIONS = "key_pinned_sessions"
        private const val KEY_ARCHIVED_SESSIONS = "key_archived_sessions"
        private const val KEY_CUSTOM_WORKSPACES = "key_custom_workspaces"
        private const val KEY_MODEL_OVERRIDES = "key_model_overrides"
    }
}

