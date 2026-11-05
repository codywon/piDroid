package com.pi.app.data.model

/**
 * 模型参数的内置默认值。
 *
 * **为什么要单独有这个文件**：`200000L`（上下文窗口）此前散落在 6 个文件、11 处 ——
 * `Models.kt` 的三个 data class 默认值、快照解析的两处兜底、`AppPreferences` 的模型覆盖读取、
 * `PiApiClient` 的目录合并与内置目录、以及设置页输入框的初始值与解析兜底。
 *
 * 散落的危害不是「多打几个字符」，而是**改一处不改另一处**：真要把默认窗口调成 256K，
 * 界面初始值、快照解析兜底和目录合并会各说各话，表现成「同一台设备上上下文上限时大时小」。
 *
 * 这些值只在「服务端没有给出」时使用。网关（pi-web）一旦上报了 `contextWindow`，
 * 就必须以服务端为准 —— 它们不是可以被随意切换的偏好，而是兜底。
 */
object ModelDefaults {

    /** 未上报时的上下文窗口（pi 主流的 200K 档）。 */
    const val DEFAULT_CONTEXT_WINDOW = 200_000L

    /** 未上报时的单轮最大输出 token。 */
    const val DEFAULT_MAX_OUTPUT_TOKENS = 8_192L
}
