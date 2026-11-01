package com.pi.app.ui.components

/**
 * 公式渲染页面的 HTML 模板（KaTeX）。
 *
 * 抽成不依赖 Android 的纯 Kotlin：转义、缩放与尺寸上报这三段逻辑最容易写错，
 * 放在这里才能在 `app/src/test/` 里用纯 JVM 测试守住（本机无 Android SDK）。
 *
 * 页面里的 `https://pi-katex.local/…` 是**假域名**：宿主 WebView 的
 * `shouldInterceptRequest` 会把它全部映射到 `assets/libs/katex/`，因此完全离线可用，
 * 也不会真的发出任何网络请求。
 */
object KaTeXPageTemplate {

    const val ASSET_HOST: String = "pi-katex.local"

    const val CSS_URL: String = "https://$ASSET_HOST/katex.min.css"

    const val JS_URL: String = "https://$ASSET_HOST/katex.min.js"

    /** 把任意文本安全地塞进 JS 双引号字符串字面量。 */
    internal fun escapeForJsString(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // `<` / `>` / `&` 一律转成 JS 转义序列：
                // 公式里一旦出现 `</script>`，内联脚本会被提前截断，整页白板。
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 只接受 `#RGB` / `#RRGGBB` / `#AARRGGBB`，其余一律退回黑色，避免 CSS 注入。 */
    internal fun sanitizeCssColor(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("#")) return "#000000"
        val hex = trimmed.substring(1)
        if (hex.isEmpty() || hex.length > 8) return "#000000"
        if (!hex.all { it.isDigit() || (it in 'a'..'f') || (it in 'A'..'F') }) return "#000000"
        return "#$hex"
    }

    /**
     * 生成自包含的公式页面。
     *
     * @param latex 原始 LaTeX（不含 `$$` 定界符）
     * @param displayMode 块级公式 vs 行内公式
     * @param fontSizePx 正文字号（CSS px，与 dp 等值）
     * @param colorHex 前景色 `#RRGGBB`
     */
    fun build(
        latex: String,
        displayMode: Boolean,
        fontSizePx: Float,
        colorHex: String
    ): String {
        val color = sanitizeCssColor(colorHex)
        val size = if (fontSizePx.isFinite() && fontSizePx > 0f) fontSizePx else 15f
        val tex = escapeForJsString(latex)
        val display = if (displayMode) "true" else "false"
        return """
<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<link rel="stylesheet" href="$CSS_URL">
<style>
html, body { margin: 0; padding: 0; background: transparent; overflow: hidden; }
body { -webkit-text-size-adjust: 100%; }
#wrap { display: inline-block; padding: 1px 0; color: $color; font-size: ${size}px; }
.katex { color: $color; }
.katex-display { margin: 0 !important; }
</style>
</head>
<body>
<div id="wrap"></div>
<script src="$JS_URL"></script>
<script>
(function () {
  var LATEX = "$tex";
  var DISPLAY = $display;
  var wrap = document.getElementById('wrap');
  window.__PI_MATH_OK = 0;
  window.__PI_MATH_W = 0;
  window.__PI_MATH_H = 0;

  function fit() {
    wrap.style.transform = '';
    wrap.style.transformOrigin = 'top left';
    if (DISPLAY) { wrap.style.textAlign = ''; }
    var inner = wrap.querySelector('.katex') || wrap;
    var rect = inner.getBoundingClientRect();
    var avail = document.documentElement.clientWidth || window.innerWidth || 0;
    // display 模式下 .katex-display 是块级、宽度铺满，必须取内部 .katex 的自然宽度，
    // 否则「公式比屏幕宽」这件事永远测不出来，只能眼睁睁看它被裁掉。
    var w = Math.ceil(Math.max(rect.width, inner.scrollWidth || 0));
    var h = Math.ceil(Math.max(rect.height, inner.scrollHeight || 0));
    if (w > 0 && avail > 0 && w > avail) {
      var scale = avail / w;
      // 居中布局在缩放后会两边一起溢出，先靠左再缩
      if (DISPLAY) { wrap.style.textAlign = 'left'; }
      wrap.style.transform = 'scale(' + scale + ')';
      w = Math.ceil(w * scale);
      h = Math.ceil(h * scale);
    }
    window.__PI_MATH_W = w;
    window.__PI_MATH_H = h;
    document.body.style.height = h + 'px';
  }

  window.__PI_MATH_FIT = fit;
  window.__PI_MATH_DIMS = function () {
    return window.__PI_MATH_OK + '|' + window.__PI_MATH_W + '|' + window.__PI_MATH_H;
  };

  try {
    if (!window.katex || typeof window.katex.render !== 'function') {
      throw new Error('katex-missing');
    }
    window.katex.render(LATEX, wrap, {
      displayMode: DISPLAY,
      throwOnError: false,
      errorColor: '$color',
      strict: 'ignore',
      trust: false,
      output: 'html'
    });
    fit();
    window.__PI_MATH_OK = 1;
  } catch (e) {
    window.__PI_MATH_OK = 0;
    window.__PI_MATH_ERR = String(e && e.message ? e.message : e);
  }
  if (document.fonts && document.fonts.ready && document.fonts.ready.then) {
    document.fonts.ready.then(function () { try { fit(); } catch (e) {} });
  }
})();
</script>
</body></html>
""".trimIndent()
    }
}
