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
 * 为什么窗口是 120 而不是 40，也不是「全量」：40 太激进，普通长回合（跑测试 / 改十几个文件那种，
 * 过程项几十条）会被截掉一大截，看上去像「App 只同步了最近几步」——而 pi-web 的
 * `ProcessDetailsGroup` 展开时是**全量渲染、没有截断**的。但也不能真成全量：该策略存在的
 * 原因就是「上百张卡片同一帧组合」会卡（CAD 出图那类回合实测掉帧）。120 覆盖绝大多数真实回合，
 * 超过时仍有「显示更早的 N 条过程」兜底，组合量仍是有界的。
 *
 * 为什么不直接把组内换成嵌套 LazyColumn：纵向列表套纵向列表会抢手势，而且窗口化会改变
 * 行 key 与滚动锚定（本项目此前那套「按条数切窗口」正因为此被回滚过）。
 */
object ProcessWindowPolicy {

    /** 首次展开时渲染的过程条数（超过就折叠更早的部分）。 */
    const val INITIAL_WINDOW = 120

    /** 每点一次「显示更早」补充的条数。 */
    const val WINDOW_STEP = 80

    /**
     * 渲染窗口上限 = `min(总数, INITIAL_WINDOW + 用户已展开的步数 * WINDOW_STEP)`。
     *
     * 为什么用「已展开的步数」而不是「上一次的窗口值」：[extraSteps] 只能被用户点击 +
     * 加，而窗口每帧都由**当前总数**重算 —— 回合是活的（每几秒多一条过程项），
     * 一旦把绝对窗口值缓存起来（例如 `remember { initialLimit(total) }`），
     * 它就会冻在首次组合时那个 1~2 上，界面就变成「12 条消息只显示最新 1 条 +
     * 显示更早的 11 条过程」。
     */
    fun limitFor(
        total: Int,
        extraSteps: Int,
        window: Int = INITIAL_WINDOW,
        step: Int = WINDOW_STEP
    ): Int {
        if (total <= 0) return 0
        val base = if (window <= 0) total else window
        val steps = extraSteps.coerceAtLeast(0)
        val extra = if (step <= 0) total else steps.toLong() * step
        val limit = base.toLong() + extra
        return if (limit >= total) total else limit.toInt()
    }

    /** 还有多少条被折叠（渲染窗口 `_显示更早的 N 条_` 文案用）。 */
    fun hiddenCount(total: Int, limit: Int): Int = (total - limit).coerceAtLeast(0)
}
