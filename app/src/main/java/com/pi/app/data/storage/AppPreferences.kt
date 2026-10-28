package com.pi.app.data.storage

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    /** 应用缓存目录：会话快照离线缓存放在这里（不占用户可见空间，系统可回收） */
    val cacheDir: java.io.File = context.cacheDir

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

    // 任务完成独立弹出通知开关：默认关闭 (常驻通知栏已有实时统计、状态、声音与震动；开启后附带精确任务名称)
    var taskCompletedNotifyEnabled: Boolean
        get() = prefs.getBoolean(KEY_TASK_COMPLETED_NOTIFY, false)
        set(value) = prefs.edit().putBoolean(KEY_TASK_COMPLETED_NOTIFY, value).apply()

    // 应用更新是否允许使用第三方加速镜像：默认关闭。
    // 开启后安装包会经由第三方代理中转，存在被投毒风险（虽仍会做 SHA-256 与签名校验）。
    var useUpdateMirrors: Boolean
        get() = prefs.getBoolean(KEY_USE_UPDATE_MIRRORS, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_UPDATE_MIRRORS, value).apply()

    // 输入框草稿：按会话隔离保存，防止旋转/切主题/进程回收后丢失未发送内容
    fun getSessionDraft(sessionId: String): String {
        if (sessionId.isBlank()) return ""
        return prefs.getString("draft_$sessionId", "") ?: ""
    }

    fun saveSessionDraft(sessionId: String, text: String) {
        if (sessionId.isBlank()) return
        val key = "draft_$sessionId"
        prefs.edit().apply {
            if (text.isBlank()) remove(key) else putString(key, text)
        }.apply()
    }

    var defaultModelId: String
        get() = prefs.getString(KEY_DEFAULT_MODEL, "gpt-6.1-sol") ?: "gpt-6.1-sol"
        set(value) = prefs.edit().putString(KEY_DEFAULT_MODEL, value).apply()

    var defaultProvider: String
        get() = prefs.getString(KEY_DEFAULT_PROVIDER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEFAULT_PROVIDER, value).apply()

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

    /** 用服务端 ui-state 覆盖本地镜像（置顶） */
    fun setPinnedSessionIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_PINNED_SESSIONS, ids).apply()
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

    // 未读标记（与 pi-web 一致：存在设备本地，不跨端同步）
    fun getUnreadSessionIds(): Set<String> {
        return prefs.getStringSet(KEY_UNREAD_SESSIONS, emptySet()) ?: emptySet()
    }

    fun markSessionRead(sessionId: String) {
        val current = getUnreadSessionIds().toMutableSet()
        if (current.remove(sessionId)) {
            prefs.edit().putStringSet(KEY_UNREAD_SESSIONS, current).apply()
        }
    }

    fun markSessionUnread(sessionId: String) {
        val current = getUnreadSessionIds().toMutableSet()
        if (current.add(sessionId)) {
            prefs.edit().putStringSet(KEY_UNREAD_SESSIONS, current).apply()
        }
    }

    // 会话归档管理 (对标 pi-web 侧边栏底部已归档体系)
    fun getArchivedSessionIds(): Set<String> {
        return prefs.getStringSet(KEY_ARCHIVED_SESSIONS, emptySet()) ?: emptySet()
    }

    fun isSessionArchived(sessionId: String): Boolean {
        return getArchivedSessionIds().contains(sessionId)
    }

    /** 用服务端 ui-state 覆盖本地镜像（归档） */
    fun setArchivedSessionIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_ARCHIVED_SESSIONS, ids).apply()
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
            .remove("draft_$sessionId")
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


    // 工作区项目置顶管理 (对标 pi-web 项目置顶)
    fun getPinnedWorkspaces(): Set<String> {
        return prefs.getStringSet(KEY_PINNED_WORKSPACES, emptySet()) ?: emptySet()
    }

    fun isWorkspacePinned(path: String): Boolean {
        return getPinnedWorkspaces().contains(path.trim())
    }

    fun togglePinWorkspace(path: String): Boolean {
        val current = getPinnedWorkspaces().toMutableSet()
        val clean = path.trim()
        val willPin = !current.contains(clean)
        if (willPin) {
            current.add(clean)
        } else {
            current.remove(clean)
        }
        prefs.edit().putStringSet(KEY_PINNED_WORKSPACES, current).apply()
        return willPin
    }

    // 工作区自定义友好别名 (对标 pi-web rename-project & reset-project-name)
    fun getWorkspaceCustomName(path: String): String? {
        return prefs.getString("ws_name_${path.trim()}", null)?.takeIf { it.isNotBlank() }
    }

    fun saveWorkspaceCustomName(path: String, name: String?) {
        val clean = path.trim()
        if (name.isNullOrBlank()) {
            prefs.edit().remove("ws_name_$clean").apply()
        } else {
            prefs.edit().putString("ws_name_$clean", name.trim()).apply()
        }
    }

    // 工作区自定义排序 (对标 pi-web move-up / move-down)
    fun getWorkspaceOrder(): List<String> {
        val raw = prefs.getString(KEY_WORKSPACE_ORDER, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(";").filter { it.isNotBlank() }
    }

    fun saveWorkspaceOrder(order: List<String>) {
        val raw = order.joinToString(";")
        prefs.edit().putString(KEY_WORKSPACE_ORDER, raw).apply()
    }

    companion object {
        private const val KEY_SERVER_URL = "key_server_url"
        private const val KEY_AUTH_TOKEN = "key_auth_token"
        private const val KEY_DEVICE_NAME = "key_device_name"
        private const val KEY_AUTO_SPEAK = "key_auto_speak"
        private const val KEY_PERSISTENT_NOTIFY = "key_persistent_notify"
        private const val KEY_HAPTICS_ENABLED = "key_haptics_enabled"
        private const val KEY_COMPLETION_SOUND_ENABLED = "key_completion_sound_enabled"
        private const val KEY_TASK_COMPLETED_NOTIFY = "key_task_completed_notify"
        private const val KEY_USE_UPDATE_MIRRORS = "key_use_update_mirrors"
        private const val KEY_DEFAULT_MODEL = "key_default_model"
        private const val KEY_DEFAULT_PROVIDER = "key_default_provider"
        private const val KEY_DEFAULT_THINKING = "key_default_thinking"
        private const val KEY_DEFAULT_TOOL_PRESET = "key_default_tool_preset"
        private const val KEY_PINNED_SESSIONS = "key_pinned_sessions"
        private const val KEY_ARCHIVED_SESSIONS = "key_archived_sessions"
        private const val KEY_UNREAD_SESSIONS = "key_unread_sessions"
            private const val KEY_PINNED_WORKSPACES = "pinned_workspaces"
    private const val KEY_WORKSPACE_ORDER = "workspace_order"
    private const val KEY_CUSTOM_WORKSPACES = "key_custom_workspaces"
        private const val KEY_MODEL_OVERRIDES = "key_model_overrides"
    }
}

