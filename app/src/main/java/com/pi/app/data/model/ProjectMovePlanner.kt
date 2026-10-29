package com.pi.app.data.model

/** 项目排序方向（对应 pi-web 项目菜单里的 move-up / move-down） */
enum class ProjectMoveDirection { UP, DOWN }

/**
 * 一次 move-project 请求的内容：
 * `POST /api/sessions/ui-state {"action":"move-project","projectKey":..,"anchorKey":..,"position":..,"add":[..]}`
 *
 * @param projectKey 被移动的项目 key（小写、正斜杠绝对路径）
 * @param anchorKey 锚点项目 key —— **必须取「移动之前」的邻居**；
 *                  若锚点算在了移动之后，锚点会等于自己，服务端直接 400
 *                  `projectKey and anchorKey must differ`。
 * @param position [BEFORE] 放到锚点之前、[AFTER] 放到锚点之后
 */
data class ProjectMovePlan(
    val projectKey: String,
    val anchorKey: String,
    val position: String
) {
    companion object {
        const val BEFORE = "before"
        const val AFTER = "after"
    }
}

/**
 * 项目（工作区）排序计划器 —— 逐行对齐 pi-web `SessionSidebar` 里的：
 *
 * ```js
 * function moveAnchor(projects, key, dir) {
 *   const target = projects.find((p) => p.key === key);
 *   const band = projects.filter((p) => p.pinned === target.pinned);
 *   const idx = band.indexOf(target);
 *   const anchor = band[dir === "up" ? idx - 1 : idx + 1];
 *   return anchor ? { anchorKey: anchor.key, position: dir === "up" ? "before" : "after" } : null;
 * }
 * ```
 *
 * 服务端 `ui-state` 对 move-project 的校验（pi-web 源码）：
 * 1) `projectKey` / `anchorKey` 均为非空字符串；
 * 2) `projectKey !== anchorKey` —— 否则 400 "projectKey and anchorKey must differ"；
 * 3) `position` 必须是 "before" 或 "after" —— 缺省即 400；
 * 4) 只在同一 pinned 分组内的相邻项目之间移动。
 */
object ProjectMovePlanner {

    /** 服务端 `add` 最多接受 500 个 key */
    const val MAX_ADD_KEYS = 500

    /**
     * 计算一次相邻移动的请求；不可移动（已在分组首/末、或不在分组里）返回 null。
     *
     * @param bandKeys 同一 pinned 分组内、**按当前展示顺序**排列的项目 key
     * @param projectKey 被移动项目的 key
     * @param direction 上移 / 下移
     */
    fun plan(
        bandKeys: List<String>,
        projectKey: String,
        direction: ProjectMoveDirection
    ): ProjectMovePlan? {
        val index = bandKeys.indexOf(projectKey)
        if (index < 0) return null
        val anchorIndex = if (direction == ProjectMoveDirection.UP) index - 1 else index + 1
        val anchorKey = bandKeys.getOrNull(anchorIndex) ?: return null
        if (anchorKey == projectKey) return null
        return ProjectMovePlan(
            projectKey = projectKey,
            anchorKey = anchorKey,
            position = if (direction == ProjectMoveDirection.UP) ProjectMovePlan.BEFORE else ProjectMovePlan.AFTER
        )
    }

    /**
     * 与 pi-web 的 `add: unorderedKeysByBand[band].slice(-500)` 等价：
     * 把「同分组内、尚未写进 projectOrder」的项目 key 一并提交，
     * 这样 PC 端按当前展示顺序补齐排序时，不会因为只写了两个 key 而整体跳动。
     *
     * @param bandKeys 同分组内按展示顺序排列的项目 key
     * @param orderedKeys 服务端已有 projectOrder 中的 key
     * @param exclude 已经由 projectKey / anchorKey 表达的 key
     */
    fun collectAddKeys(
        bandKeys: List<String>,
        orderedKeys: Collection<String>,
        vararg exclude: String
    ): List<String> {
        if (bandKeys.isEmpty()) return emptyList()
        val ordered = orderedKeys.toHashSet()
        val excluded = exclude.toHashSet()
        return bandKeys
            .filter { it.isNotEmpty() && it !in ordered && it !in excluded }
            .takeLast(MAX_ADD_KEYS)
    }
}
