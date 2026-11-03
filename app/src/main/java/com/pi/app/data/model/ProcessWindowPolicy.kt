package com.pi.app.data.model

/**
 * 长回合「过程组」的渲染窗口策略（纯 JVM，可单测）。
 *
 * 病根：一个回合的全部过程项（思考 / 工具调用 / 中间答复）在列表里是**单个 item**，
 * 组内是普通 Column + forEach —— 展开时该回合的所有卡片在同一帧组合出来。像
 * 「组装车间 UWB 定位施工图」那种一个回合上百次工具调用的会话，展开一次就是上百张卡片
 * （外加其中的图片）。图片侧已由「进视口才加载」兜住，这里再把**组合量**本身压到上限级：
 * 只渲染最近的 [INITIAL_WINDOW] 条，更早的用一行「显示更早的 N 条过程」按 [WINDOW_STEP] 递增展开。
 *
 * 为什么窗口从 40 提到 200：40 太激进，普通长回合（跑测试 / 改十几个文件那种，过程项几十条）
 * 会被直接截掉一大截，看上去像「App 只同步了最近几步」——而 pi-web 的 `ProcessDetailsGroup`
 * 展开时是**全量渲染、没有截断**的。200 已经覆盖绝大多数真实回合（超过 200 条过程项意味着
 * 一个回合里两百次工具调用），此时兜底行仍在，不会变成无界渲染。
 *
 * 为什么不直接把组内换成嵌套 LazyColumn：纵向列表套纵向列表会抢手势，而且窗口化会改变
 * 行 key 与滚动锚定（本项目此前那套「按条数切窗口」正因为此被回滚过）。
 */
object ProcessWindowPolicy {

    /** 首次展开时渲染的过程条数（超过就折叠更早的部分）。 */
    const val INITIAL_WINDOW = 200

    /** 每点一次「显示更早」补充的条数。 */
    const val WINDOW_STEP = 100

    /** 首次展开时实际渲染多少条：不超过总数，也不超过窗口。 */
    fun initialLimit(total: Int, window: Int = INITIAL_WINDOW): Int {
        if (total <= 0) return 0
        val cap = if (window <= 0) total else window
        return minOf(total, cap)
    }

    /** 点一次「显示更早」之后的新窗口上限（单调不减，封顶总数）。 */
    fun nextLimit(current: Int, total: Int, step: Int = WINDOW_STEP): Int {
        if (total <= 0) return 0
        val base = current.coerceAtLeast(0)
        val next = base + (if (step <= 0) total else step)
        return next.coerceAtMost(total)
    }

    /** 还有多少条被折叠（渲染窗口 `_显示更早的 N 条_` 文案用）。 */
    fun hiddenCount(total: Int, limit: Int): Int = (total - limit).coerceAtLeast(0)
}
