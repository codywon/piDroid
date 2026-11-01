package com.pi.app.data.model

/**
 * 左侧栏「长按拖动排序」的纯计算部分 —— 不引用 Compose / Android，可直接用 JVM 单测覆盖。
 *
 * 守护的 bug：旧实现在「跨过半个行高」时就调一次相邻移动，并把跟随手指的位移读数**归零**。
 * 但行一旦换位，它在列表里的格子已经上/下移了一整行，读数归零等于让这一行瞬间回弹半行 ——
 * 拖动时看起来就是一顿一跳、不跟手。
 *
 * 这里改成：
 * 1) 换位后把读数减去那一行所占的高度（邻居行高 + 行间距），视觉位置保持不变，跟手连续；
 * 2) 交换阈值取「自己的中心越过邻居的中心」，换位后残留量接近 0，天然带迟滞，不会来回抖。
 *
 * 另外把「落位后的分组顺序」反推成一条 `move-project`（锚点 + before/after）：
 * 锚点取落位之后紧邻的同分组邻居，它不会被这次移动改变相对位置，
 * 因此一次请求就能把项目放到任意位置，且 `anchorKey != projectKey`（服务端不接受自锚点）。
 */
object WorkspaceDragReorder {

    /** 一次换位的结果：新顺序 + 抹掉已换行高度后的残位移（保证视觉位置不变） */
    data class Step(val order: List<String>, val offset: Float)

    /** `move-project` 的锚点参数 */
    data class Anchor(val anchorKey: String, val position: String)

    /**
     * 尽可能多地把被拖行与相邻行交换，直到位移读数回到阈值内。
     *
     * @param order 当前展示顺序（项目路径列表）
     * @param draggedPath 被拖动的行
     * @param offset 相对自身格子的累计位移（像素，向下为正）
     * @param rowGap 相邻行之间的间距（像素）
     * @param heightOf 行高（像素）；估不到时可返回同一个兜底值
     * @param sameBand 两行是否属于同一（置顶 / 非置顶）分组；跨分组不换位
     */
    fun settle(
        order: List<String>,
        draggedPath: String,
        offset: Float,
        rowGap: Float,
        heightOf: (String) -> Float,
        sameBand: (String) -> Boolean
    ): Step {
        var index = order.indexOf(draggedPath)
        if (index < 0) return Step(order, offset)
        val current = order.toMutableList()
        val selfHeight = heightOf(draggedPath)
        var rest = offset
        while (true) {
            if (rest > 0f) {
                val neighbor = current.getOrNull(index + 1) ?: break
                if (!sameBand(neighbor)) break
                val neighborHeight = heightOf(neighbor)
                // 自己的中心越过下一行的中心才换位
                if (rest < selfHeight / 2f + rowGap + neighborHeight / 2f) break
                current[index] = neighbor
                current[index + 1] = draggedPath
                index += 1
                // 换位后自己的格子下移了「邻居行高 + 行间距」，读数同步减掉
                rest -= neighborHeight + rowGap
            } else if (rest < 0f) {
                val neighbor = current.getOrNull(index - 1) ?: break
                if (!sameBand(neighbor)) break
                val neighborHeight = heightOf(neighbor)
                if (rest > -(selfHeight / 2f + rowGap + neighborHeight / 2f)) break
                current[index] = neighbor
                current[index - 1] = draggedPath
                index -= 1
                // 换位后自己的格子上移了「邻居行高 + 行间距」，读数同步补回
                rest += neighborHeight + rowGap
            } else {
                break
            }
        }
        return Step(current, rest)
    }

    /**
     * 由「落位之后的同分组顺序」反推一条 `move-project` 请求。
     *
     * @param finalBandKeys 同一（置顶 / 非置顶）分组内、按落位后展示顺序排列的项目 key
     * @param draggedKey 被拖动的项目 key
     * @return 无法表达（不在分组里 / 分组只有它自己）时返回 null
     */
    fun anchorFor(finalBandKeys: List<String>, draggedKey: String): Anchor? {
        val index = finalBandKeys.indexOf(draggedKey)
        if (index < 0) return null
        val rest = finalBandKeys.filter { it != draggedKey }
        if (rest.isEmpty()) return null
        return if (index == 0) {
            // 落位在分组首个：放到后一个之前
            Anchor(rest.first(), ProjectMovePlan.BEFORE)
        } else {
            // 其余情况：放到落位前一个之后
            Anchor(rest[index - 1], ProjectMovePlan.AFTER)
        }
    }
}
