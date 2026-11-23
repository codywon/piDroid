package com.pi.app.data.model

/**
 * 剥除模型写进正文/思考里的终端转义（对齐 pi-web `lib/ansi.ts` 的 `stripAnsi`，#1753）。
 *
 * 为什么需要：provider 会把 ANSI 序列塞进回答（实测有模型把 `\x1b[38;5;5mThinking:\x1b[39m`
 * 当作推理前缀）。pi 原样存盘，pi 自己的 TUI 把这段交给终端按样式画；浏览器/Compose 只会把
 * ESC 之后的 `[38;5;5m` 当普通字符显示，于是正文里出现 `[38;5;5mThinking:[39m` 这种噪音。
 * pi-web 在 `TextBlock`、`ThinkingText`、复制、thinking 预览处统一 strip，这里对齐同一口径。
 */
private val TUI_CURSOR_MARKER = Regex("\u001B_pi:[^\u0007\u001B\n]*(?:\u0007|\u001B\\\\)")
// 第三条分支与 pi-web 逐字一致：单字符转义只认 ESC + ( @-Z \\ ] ^ _ )，`-` 之类不动。
// 类里首个 `]` 是字面量（不写转义），避免 `\\-_` 这种在 Java/JS 里含义不同的写法。
private val ANSI_ESCAPE = Regex("\u001B(?:\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[]@-Z\\\\^_])")

fun stripAnsi(text: String): String {
    // 整段正文上调用：没有 ESC 就跳过两次扫描（与 pi-web 同样的短路）。
    if (!text.contains('\u001B')) return text
    return ANSI_ESCAPE.replace(TUI_CURSOR_MARKER.replace(text, ""), "")
}
