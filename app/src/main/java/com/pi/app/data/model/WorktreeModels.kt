package com.pi.app.data.model

import com.google.gson.JsonObject

/**
 * 单个 Git Worktree 检出信息（1:1 对标 pi-web GET /api/worktrees 返回的项）。
 */
data class WorktreeItem(
    val path: String,
    val branch: String?,
    val isCurrent: Boolean = false,
    val isDetached: Boolean = false,
    val isLocked: Boolean = false,
    val isPrunable: Boolean = false,
    val isMain: Boolean = false
)

/**
 * 项目的完整 Worktree 检出集合与 Git 根状态。
 */
data class WorktreesResult(
    val projectRoot: String,
    val projectKey: String,
    val isGit: Boolean = false,
    val isTopLevel: Boolean = false,
    val currentWorktreePath: String? = null,
    val worktrees: List<WorktreeItem> = emptyList()
)

object WorktreeResponseParsing {

    fun parse(json: JsonObject): WorktreesResult {
        val projectRoot = json.get("projectRoot")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val projectKey = json.get("projectKey")?.takeIf { !it.isJsonNull }?.asString ?: projectRoot
        val isGit = json.get("isGit")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        val isTopLevel = json.get("isTopLevel")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        val currentPath = json.get("currentWorktreePath")?.takeIf { !it.isJsonNull }?.asString

        val list = mutableListOf<WorktreeItem>()
        json.get("worktrees")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val path = obj.get("path")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            val branch = obj.get("branch")?.takeIf { !it.isJsonNull }?.asString
            val isCur = obj.get("isCurrent")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            val isDet = obj.get("isDetached")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            val isLoc = obj.get("isLocked")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            val isPru = obj.get("isPrunable")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            val isMain = obj.get("isMain")?.takeIf { !it.isJsonNull }?.asBoolean
                ?: (path.equals(projectRoot, ignoreCase = true))

            list.add(
                WorktreeItem(
                    path = path,
                    branch = branch,
                    isCurrent = isCur,
                    isDetached = isDet,
                    isLocked = isLoc,
                    isPrunable = isPru,
                    isMain = isMain
                )
            )
        }

        return WorktreesResult(
            projectRoot = projectRoot,
            projectKey = projectKey,
            isGit = isGit,
            isTopLevel = isTopLevel,
            currentWorktreePath = currentPath,
            worktrees = list
        )
    }
}
