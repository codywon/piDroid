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

    /**
     * 判断当前被拖动的行是否还可以继续向上（往列表头部）换位。
     * 只有当它上方存在相邻行且属于同一分组时，才可以向上换位。
     */
    fun canSwapUp(
        order: List<String>,
        draggedPath: String,
        sameBand: (String) -> Boolean
    ): Boolean {
        val index = order.indexOf(draggedPath)
        if (index <= 0) return false
        return sameBand(order[index - 1])
    }

    /**
     * 判断当前被拖动的行是否还可以继续向下（往列表尾部）换位。
     * 只有当它下方存在相邻行且属于同一分组时，才可以向下换位。
     */
    fun canSwapDown(
        order: List<String>,
        draggedPath: String,
        sameBand: (String) -> Boolean
    ): Boolean {
        val index = order.indexOf(draggedPath)
        if (index < 0 || index >= order.size - 1) return false
        return sameBand(order[index + 1])
    }

    /**
     * 计算拖拽至列表顶部/底部边缘时的自动滚动速度（像素/秒，向上滚动为负，向下滚动为正；不在边缘或无法滚动时返回 0f）。
     *
     * @param fingerY 当前手指/拖拽焦点在视口内的绝对 Y 坐标（像素）
     * @param viewportHeight 视口总高度（像素）
     * @param edgeZonePx 边缘感应区厚度（像素）
     * @param maxSpeedPxPerSec 靠近边缘时的最大滚动速度（像素/秒）
     * @param minSpeedPxPerSec 刚进入边缘感应区时的起步滚动速度（像素/秒）
     * @param canScrollUp 是否允许向上滚动（列表还有前面内容且能向上换位）
     * @param canScrollDown 是否允许向下滚动（列表还有后面内容且能向下换位）
     */
    fun calculateScrollSpeed(
        fingerY: Float,
        viewportHeight: Float,
        edgeZonePx: Float,
        maxSpeedPxPerSec: Float,
        minSpeedPxPerSec: Float = maxSpeedPxPerSec * 0.25f,
        canScrollUp: Boolean,
        canScrollDown: Boolean
    ): Float {
        if (viewportHeight <= 0f || edgeZonePx <= 0f) return 0f
        // 保证感应区不超过视口的一半，防止小屏幕/分屏下上下感应区重叠
        val effectiveZone = minOf(edgeZonePx, viewportHeight / 2f)

        // 顶部边缘感应区：[0, effectiveZone]，越接近顶部（或超出顶部）滚动越快
        if (canScrollUp && fingerY < effectiveZone) {
            val ratio = ((effectiveZone - fingerY) / effectiveZone).coerceIn(0f, 1f)
            val speed = minSpeedPxPerSec + (maxSpeedPxPerSec - minSpeedPxPerSec) * ratio
            return -speed
        }

        // 底部边缘感应区：[viewportHeight - effectiveZone, viewportHeight]，越接近底部（或超出底部）滚动越快
        val bottomTrigger = viewportHeight - effectiveZone
        if (canScrollDown && fingerY > bottomTrigger) {
            val ratio = ((fingerY - bottomTrigger) / effectiveZone).coerceIn(0f, 1f)
            val speed = minSpeedPxPerSec + (maxSpeedPxPerSec - minSpeedPxPerSec) * ratio
            return speed
        }

        return 0f
    }

    /**
     * 当被拖动行已到达分组边界无法继续换位时，对多余的拖拽位移施加阻尼限制，
     * 避免卡片飞出列表过远导致松手时产生大幅度猛弹回。
     */
    fun clampResidualOffset(
        offset: Float,
        canSwapUp: Boolean,
        canSwapDown: Boolean,
        maxOvershootPx: Float
    ): Float {
        val limit = kotlin.math.abs(maxOvershootPx)
        if (offset < 0f && !canSwapUp) {
            return (-limit).coerceAtLeast(offset)
        }
        if (offset > 0f && !canSwapDown) {
            return offset.coerceAtMost(limit)
        }
        return offset
    }
}
