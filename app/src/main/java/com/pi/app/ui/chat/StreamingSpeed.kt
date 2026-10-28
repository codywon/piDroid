package com.pi.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.ChatItem
import com.pi.app.data.model.MessageRole
import com.pi.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 流式输出实时指标（1:1 复刻 pi-web MessageView.tsx 的 assistant 头部）：
 *
 * ```
 * deepseek-v41-flash  ↓ 1540  [177.7 t/s]
 * ```
 *
 * pi-web 原始实现（.next/static/chunks/app/page-*.js 反查）：
 * - token 估算：全角字符记 1、其余记 1/4（不调分词器）；按 block 增量估算，只算新增尾巴
 * - 速率采样：300ms 一次；首帧记录 baseline（`base ??= …`，只记一次、不滚动），
 *   已经过 > 0.5s 才出数，所以显示的是「自流式开始以来的累计均速」
 * - 配色阈值：>=50 / >=30 / >=15 / 其他 四档
 * - 流式结束：速率清空（token 数一并消失），baseline 缓存保留以便 remount 后接着算
 */
private object TokenEstimate {
    /** pi-web 的宽字符正则：[\u3000-\u30ff\u3400-\u9fff\uf900-\ufaff\u{20000}-\u{2fa1f}\uac00-\ud7af] */
    private fun isWide(cp: Int): Boolean =
        cp in 0x3000..0x30FF ||   // CJK 标点 + 平假名 + 片假名
            cp in 0x3400..0x9FFF || // CJK 扩展 A + 基本区
            cp in 0xF900..0xFAFF || // CJK 兼容表意文字
            cp in 0x20000..0x2FA1F || // CJK 扩展 B~F + 兼容补充
            cp in 0xAC00..0xD7AF    // 谚文音节

    fun of(text: CharSequence): Double {
        if (text.isEmpty()) return 0.0
        var wide = 0
        var narrow = 0
        var i = 0
        // 按 code point 遍历（对齐 JS 的 for...of），避免 emoji 的代理对被拆成两个字符计数
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (isWide(cp)) wide++ else narrow++
            i += Character.charCount(cp)
        }
        return wide + narrow / 4.0
    }
}

/**
 * 增量 token 累加器：新文本是旧文本的延伸时只估算尾巴。
 * 对应 pi-web 的 `function cached(e,t){ if(!t.startsWith(e.text)) return sf(t); … }`，
 * 包含「切在代理对中间要先退回 1/4」的保护，否则 emoji 会被重复计一次。
 */
private class IncrementalTokenCounter {
    private var text: String = ""
    private var tokens: Double = 0.0

    fun update(newText: String): Double {
        if (newText == text) return tokens
        if (!newText.startsWith(text)) {
            text = newText
            tokens = TokenEstimate.of(newText)
            return tokens
        }
        var from = text.length
        var base = tokens
        if (from > 0 && from < newText.length &&
            Character.isHighSurrogate(newText[from - 1]) &&
            Character.isLowSurrogate(newText[from])
        ) {
            base -= 0.25
            from--
        }
        tokens = base + TokenEstimate.of(newText.subSequence(from, newText.length))
        text = newText
        return tokens
    }
}

/** 一次采样基线（pi-web 的 `{at, tokens}`） */
private class SpeedSample(var at: Long, var tokens: Double)

/**
 * pi-web 里 `ss` 那个模块级 Map：key = `${provider}/${model}@${timestamp}`，上限 32 条。
 * 作用：组件 remount（列表滚出再滚回）后仍能接着上一次的 baseline 算，不会速率重新起跳。
 */
private object SpeedSampleCache {
    private val map = object : LinkedHashMap<String, SpeedSample>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SpeedSample>?): Boolean =
            size > 32
    }

    @Synchronized
    fun get(key: String): SpeedSample? = map[key]

    @Synchronized
    fun put(key: String, sample: SpeedSample) {
        map[key] = sample
    }
}

/** pi-web 阈值：>=50 青、>=30 绿、>=15 黄、其余红 */
private fun tpsColor(v: Double): Color = when {
    v >= 50.0 -> Color(0xFF53B3CB)
    v >= 30.0 -> Color(0xFF9BC53D)
    v >= 15.0 -> Color(0xFFF9C22E)
    else -> Color(0xFFE01A4F)
}

/** 参与估算的正文：对齐 pi-web 的 text + thinking + toolCall(rawInput)，不含工具结果 output */
private fun ChatItem.streamingMetricText(): String {
    if (thinking.isNullOrEmpty() && toolCall == null) return content
    return buildString(content.length + (thinking?.length ?: 0) + 32) {
        append(content)
        if (!thinking.isNullOrEmpty()) append(thinking)
        toolCall?.let { tc ->
            append(tc.preview ?: "")
            append(tc.command ?: "")
        }
    }
}

/**
 * 只在流式期间显示的「↓ 估算 token + 彩色 t/s」。
 * 严格复刻 pi-web：非流式时整个组件不渲染（token 数与速率一起消失）。
 */
@Composable
fun StreamingSpeedBadge(item: ChatItem, modifier: Modifier = Modifier) {
    if (item.role != MessageRole.AGENT || !item.isStreaming) return

    val theme = LocalAppTheme.current
    val counter = remember(item.id) { IncrementalTokenCounter() }
    // 增量缓存是幂等的（同一文本重复 update 结果不变），可以安全地在组合期间推进
    val tokens = counter.update(item.streamingMetricText())
    val latestTokens = rememberUpdatedState(tokens)

    var tps by remember(item.id) { mutableStateOf<Double?>(null) }
    val cacheKey = remember(item.id, item.modelName) {
        "${item.modelName ?: ""}@${item.id}"
    }

    LaunchedEffect(item.id, item.isStreaming) {
        if (!item.isStreaming) {
            tps = null
            return@LaunchedEffect
        }
        while (true) {
            delay(300)
            val now = System.currentTimeMillis()
            val current = latestTokens.value
            if (current <= 0.0) continue
            // pi-web: D.current ??= (ss.get(id) ?? {at, tokens})——只赋一次，之后不再滚动
            val sample = SpeedSampleCache.get(cacheKey) ?: SpeedSample(now, current).also {
                SpeedSampleCache.put(cacheKey, it)
            }
            val elapsed = (now - sample.at) / 1000.0
            if (elapsed > 0.5) {
                tps = (current - sample.tokens) / elapsed
            }
        }
    }

    val rounded = Math.round(tokens)
    if (rounded <= 0L) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DownArrowGlyph(color = theme.textPrimary)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = rounded.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            color = theme.textPrimary
        )
        tps?.let { v ->
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .background(tpsColor(v), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "${String.format(Locale.US, "%.1f", v)} t/s",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White
                )
            }
        }
    }
}

/** pi-web 的 10x10 下行箭头：line (5,1.5)-(5,8.5) + polyline (2,6)-(5,8.5)-(8,6) */
@Composable
private fun DownArrowGlyph(color: Color) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val s = size.width / 10f
        val width = 1.2f * s
        drawLine(
            color = color,
            start = Offset(5f * s, 1.5f * s),
            end = Offset(5f * s, 8.5f * s),
            strokeWidth = width,
            cap = StrokeCap.Round
        )
        val head = Path().apply {
            moveTo(2f * s, 6f * s)
            lineTo(5f * s, 8.5f * s)
            lineTo(8f * s, 6f * s)
        }
        drawPath(
            path = head,
            color = color,
            style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
