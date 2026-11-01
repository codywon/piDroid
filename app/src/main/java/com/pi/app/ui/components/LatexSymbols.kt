package com.pi.app.ui.components

/**
 * LaTeX 命令 → Unicode 符号表。
 *
 * 这是**行内公式原生降级渲染**的符号来源：块级公式已经交给 KaTeX（见 `KaTeXFormulaView`），
 * 但 `$…$` 行内公式仍然走 `LatexMathParser` 这条不依赖 WebView 的轻量通道 —— 一张公式一个
 * WebView 会把长消息的显存吃穿，而行内数学绝大多数只是箭头、希腊字母、上下标。
 *
 * 刻意不 import 任何 Android / Compose 类型：纯 Kotlin，可在本机跑单元测试。
 */
object LatexSymbols {

    /** 一个已识别的命令。`binary = true` 表示二元关系/运算符，排版时前后留空格。 */
    data class Symbol(val text: String, val binary: Boolean)

    /** 二元关系与运算符（前后需要空格的）。 */
    private val BINARY: Map<String, String> = mapOf(
        "times" to "×",
        "div" to "÷",
        "cdot" to "·",
        "ast" to "∗",
        "star" to "⋆",
        "pm" to "±",
        "mp" to "∓",
        "leq" to "≤",
        "le" to "≤",
        "geq" to "≥",
        "ge" to "≥",
        "neq" to "≠",
        "ne" to "≠",
        "approx" to "≈",
        "equiv" to "≡",
        "cong" to "≅",
        "simeq" to "≃",
        "sim" to "∼",
        "propto" to "∝",
        "ll" to "≪",
        "gg" to "≫",
        "prec" to "≺",
        "succ" to "≻",
        "subseteq" to "⊆",
        "subset" to "⊂",
        "supseteq" to "⊇",
        "supset" to "⊃",
        "in" to "∈",
        "notin" to "∉",
        "ni" to "∋",
        "cup" to "∪",
        "cap" to "∩",
        "setminus" to "∖",
        "wedge" to "∧",
        "land" to "∧",
        "vee" to "∨",
        "lor" to "∨",
        "oplus" to "⊕",
        "otimes" to "⊗",
        "perp" to "⊥",
        "parallel" to "∥",
        "mid" to "∣",
        "circ" to "∘",
        "bullet" to "•",
        // 箭头：纯工程报告里出现频率最高的一类
        "to" to "→",
        "rightarrow" to "→",
        "leftarrow" to "←",
        "gets" to "←",
        "Rightarrow" to "⇒",
        "Leftarrow" to "⇐",
        "leftrightarrow" to "↔",
        "Leftrightarrow" to "⇔",
        "longrightarrow" to "⟶",
        "longleftarrow" to "⟵",
        "Longrightarrow" to "⟹",
        "Longleftrightarrow" to "⟺",
        "implies" to "⟹",
        "iff" to "⟺",
        "mapsto" to "↦",
        "uparrow" to "↑",
        "downarrow" to "↓",
        "nearrow" to "↗",
        "searrow" to "↘",
        "hookrightarrow" to "↪",
        "rightleftharpoons" to "⇌"
    )

    /** 无空格需求的符号（希腊字母、大型运算符、括号、装饰符）。 */
    private val GLYPHS: Map<String, String> = mapOf(
        "infty" to "∞",
        "partial" to "∂",
        "nabla" to "∇",
        "sum" to "∑",
        "prod" to "∏",
        "coprod" to "∐",
        "int" to "∫",
        "iint" to "∬",
        "iiint" to "∭",
        "oint" to "∮",
        "angle" to "∠",
        "triangle" to "△",
        "square" to "□",
        "therefore" to "∴",
        "because" to "∵",
        "forall" to "∀",
        "exists" to "∃",
        "nexists" to "∄",
        "neg" to "¬",
        "lnot" to "¬",
        "emptyset" to "∅",
        "varnothing" to "∅",
        "aleph" to "ℵ",
        "hbar" to "ℏ",
        "ell" to "ℓ",
        "prime" to "′",
        "degree" to "°",
        "celsius" to "℃",
        "ldots" to "…",
        "dots" to "…",
        "cdots" to "⋯",
        "vdots" to "⋮",
        "ddots" to "⋱",
        "lbrace" to "{",
        "rbrace" to "}",
        "langle" to "⟨",
        "rangle" to "⟩",
        "lfloor" to "⌊",
        "rfloor" to "⌋",
        "lceil" to "⌈",
        "rceil" to "⌉",
        "vert" to "|",
        "lvert" to "|",
        "rvert" to "|",
        "Vert" to "‖",
        "backslash" to "\\",
        "quad" to " ",
        "qquad" to "  ",
        "space" to " ",
        // 希腊字母
        "alpha" to "α",
        "beta" to "β",
        "gamma" to "γ",
        "delta" to "δ",
        "epsilon" to "ε",
        "varepsilon" to "ϵ",
        "zeta" to "ζ",
        "eta" to "η",
        "theta" to "θ",
        "vartheta" to "ϑ",
        "iota" to "ι",
        "kappa" to "κ",
        "lambda" to "λ",
        "mu" to "μ",
        "nu" to "ν",
        "xi" to "ξ",
        "omicron" to "ο",
        "pi" to "π",
        "rho" to "ρ",
        "sigma" to "σ",
        "tau" to "τ",
        "upsilon" to "υ",
        "phi" to "φ",
        "varphi" to "ϕ",
        "chi" to "χ",
        "psi" to "ψ",
        "omega" to "ω",
        "Gamma" to "Γ",
        "Delta" to "Δ",
        "Theta" to "Θ",
        "Lambda" to "Λ",
        "Xi" to "Ξ",
        "Pi" to "Π",
        "Sigma" to "Σ",
        "Upsilon" to "Υ",
        "Phi" to "Φ",
        "Psi" to "Ψ",
        "Omega" to "Ω"
    )

    /** 只影响排版、不产出字符的命令（`\left( \right)` 这类）。 */
    private val LAYOUT_ONLY: Set<String> = setOf(
        "left", "right", "big", "Big", "bigg", "Bigg",
        "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr",
        "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits",
        "nonumber", "notag", "hline", "mathstrut", "strut"
    )

    /** 需要吃掉一个 `{…}` 参数但**不显示**的命令（编号、着色、标签）。 */
    private val ARGUMENT_DROPPED: Set<String> = setOf(
        "label", "tag", "color", "phantom", "hphantom", "vphantom", "hspace", "vspace"
    )

    /** 数学函数名：输出后补一个空格，避免 `\sin x` 粘成 `sinx`。 */
    private val FUNCTIONS: Set<String> = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc",
        "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
        "log", "ln", "lg", "exp", "min", "max", "lim", "det", "gcd",
        "sup", "inf", "arg", "deg", "dim", "ker", "Pr", "mod", "bmod"
    )

    /** 解析一个命令名（不含反斜杠）；识别不了返回 null。 */
    fun resolve(command: String): Symbol? {
        BINARY[command]?.let { return Symbol(" $it ", true) }
        GLYPHS[command]?.let { return Symbol(it, false) }
        return null
    }

    fun isLayoutOnly(command: String): Boolean = LAYOUT_ONLY.contains(command)

    fun takesDroppedArgument(command: String): Boolean = ARGUMENT_DROPPED.contains(command)

    fun isFunction(command: String): Boolean = FUNCTIONS.contains(command)

    /** 给函数名补尾随空格（`\sin` → `sin `）。 */
    fun functionText(command: String): String = "$command "
}
