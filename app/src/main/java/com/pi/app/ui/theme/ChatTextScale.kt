package com.pi.app.ui.theme

import kotlin.math.roundToInt

/**
 * 「聊天正文字号」的档位计算（纯 Kotlin，不碰 Android / Compose，可在 app/src/test 直接断言）。
 *
 * 为什么是**倍数**而不是绝对值 SP：正文里的字号是一个阶梯（正文 13.5、代码 12/11.5、
 * 标题 16/15/14/13、公式 15），写成绝对值就得对每一处逐个改；用倍数则在渲染入口
 * 一次性覆盖 Compose 的 fontScale，所有阶梯按同一比例放大，行高与间距的关系也不走样。
 *
 * 档位固定 0.8 / 0.9 … 1.6 共 9 档（10% 一档）：连续滑动在手机上很难停准，
 * 也会让「回到默认」变成一次精确操作；固定档位才是可复现的（写进 SharedPreferences 的
 * 永远是干净的 0.1 倍数，不会因为浮点累加变成 1.3000001）。
 */
object ChatTextScale {

    /** 默认 100%：与历史版本完全一致的字号，升级后观感不变。 */
    const val DEFAULT: Float = 1.0f

    /** 下限 80%：再小，代码块里的标点会糊成一团。 */
    const val MIN: Float = 0.8f

    /** 上限 160%：再大，用户气泡（max 330dp）里的长词会被挤成单字符换行。 */
    const val MAX: Float = 1.6f

    /** 一档的步长。 */
    const val STEP: Float = 0.1f

    /**
     * 把任意输入规整为合法档位：NaN / Infinity 回默认，越界夹紧，再吸附到 [STEP] 网格。
     *
     * 吸附是必需的：SharedPreferences 里可能存着旧版本写下的任意 float（例如 1.25），
     * 若不吸附，界面会显示「125%」而后退/前进一步只跳到 135%，看起来像坏了。
     */
    fun normalize(raw: Float): Float {
        if (!raw.isFinite()) return DEFAULT
        val clamped = raw.coerceIn(MIN, MAX)
        val steps = ((clamped - MIN) / STEP).roundToInt()
        val snapped = MIN + steps * STEP
        // 消掉二进制浮点尾巴（0.8 + 8 * 0.1 = 1.6000001），落盘与展示都干净
        return (snapped * 10f).roundToInt() / 10f
    }

    /** 放大一档；已在 [MAX] 时原样返回（调用方据此禁用按钮）。 */
    fun zoomIn(current: Float): Float = normalize(normalize(current) + STEP)

    /** 缩小一档；已在 [MIN] 时原样返回。 */
    fun zoomOut(current: Float): Float = normalize(normalize(current) - STEP)

    /** 界面上的百分数标签，例如 `130%`。 */
    fun percentLabel(scale: Float): String = "${(normalize(scale) * 100f).roundToInt()}%"

    /** 是否已经到头（右侧 `+` 置灰用）。 */
    fun isMax(scale: Float): Boolean = normalize(scale) >= MAX

    /** 是否已经到底（左侧 `−` 置灰用）。 */
    fun isMin(scale: Float): Boolean = normalize(scale) <= MIN

    /** 正文字号（sp）在给定档位下的实际值；非法档位也保证返回 > 0。 */
    fun bodySp(scale: Float, baseSp: Float = 13.5f): Float = normalize(scale) * baseSp
}
