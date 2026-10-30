package com.pi.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式 `widget_code` 提取器回归测试。
 *
 * 守护的口径是「**任意分片边界都必须得到同一结果**」：pi-web 的 SSE 把
 * `show_widget` 的入参 JSON 按 token 切开发下来（`toolcall_delta.assistantMessageEvent.delta`），
 * 切点没有任何保证 —— 可能落在 `\u00` 中间、可能落在 `\"` 中间、也可能正好落在键名里。
 *
 * 这批用例是穷举的：对每个样本 JSON，把切点扫遍**每一个字符位置**，再叠一层逐字符喂和
 * 固定种子的随机多点切分。任何一处少写「等下一片」的分支都会被抓到。
 */
class WidgetCodeStreamTest {

    private fun oneShot(json: String): WidgetCodeStream =
        WidgetCodeStream().also { it.feed(json) }

    private fun chunked(json: String, cuts: List<Int>): WidgetCodeStream {
        val s = WidgetCodeStream()
        var prev = 0
        for (c in cuts) {
            s.feed(json.substring(prev, c))
            prev = c
        }
        s.feed(json.substring(prev))
        return s
    }

    private val simple =
        """{"i_have_seen_read_me":true,"title":"live_widget","widget_code":"<canvas id=\"c\"></canvas>","width":520}"""
    private val reordered = """{"widget_code":"<p>1</p>","title":"first"}"""
    private val decoyKeyString =
        """{"note":"the string \"widget_code\":\"x\" is data","widget_code":"<b>real</b>"}"""
    private val decoyValue =
        """{"title":"widget_code","widget_code":"<i>v</i>"}"""
    private val escaped =
        """{"widget_code":"a\"b\\c\/d\ne\tf\rg\u4e2d\u6587\ud83d\ude00"}"""
    private val expectedEscaped = "a\"b\\c/d\ne\tf\rg中文\uD83D\uDE00"

    private val samples get() = listOf(simple, reordered, decoyKeyString, decoyValue, escaped)

    @Test
    fun `whole payload in one chunk`() {
        val s = oneShot(simple)
        assertEquals("<canvas id=\"c\"></canvas>", s.value())
        assertTrue(s.isComplete)
    }

    @Test
    fun `escape sequences are decoded`() {
        assertEquals(expectedEscaped, oneShot(escaped).value())
    }

    @Test
    fun `key order does not matter`() {
        assertEquals("<p>1</p>", oneShot(reordered).value())
    }

    @Test
    fun `key name inside another string value is ignored`() {
        assertEquals("<b>real</b>", oneShot(decoyKeyString).value())
    }

    @Test
    fun `string value equal to the key name is ignored`() {
        assertEquals("<i>v</i>", oneShot(decoyValue).value())
    }

    @Test
    fun `missing key yields empty value`() {
        val s = oneShot("""{"title":"x","width":800}""")
        assertEquals("", s.value())
        assertFalse(s.isComplete)
    }

    @Test
    fun `empty string value completes`() {
        val s = oneShot("""{"widget_code":""}""")
        assertEquals("", s.value())
        assertTrue(s.isComplete)
    }

    /** 穷举：每个位置切一刀，结果必须与整串喂完全一致。 */
    @Test
    fun `every single cut point matches the one-shot result`() {
        for (json in samples) {
            val want = oneShot(json).value()
            for (k in 1 until json.length) {
                val got = chunked(json, listOf(k))
                assertEquals("cut@$k in ${json.take(24)}", want, got.value())
                assertTrue("cut@$k must complete", got.isComplete)
            }
        }
    }

    /** 最恶劣分片：一次只喂一个字符。 */
    @Test
    fun `char by char feeding matches`() {
        for (json in samples) {
            val got = chunked(json, (1 until json.length).toList())
            assertEquals(json.take(24), oneShot(json).value(), got.value())
            assertTrue(got.isComplete)
        }
    }

    /** 随机多点切分（固定种子，失败可复现）。 */
    @Test
    fun `random multi-cut feeding matches`() {
        val rnd = java.util.Random(20261029L)
        for (json in samples) {
            val want = oneShot(json).value()
            repeat(200) {
                val n = 1 + rnd.nextInt(8)
                val cuts = (0 until n).map { 1 + rnd.nextInt(json.length - 1) }.sorted().distinct()
                val got = chunked(json, cuts)
                assertEquals("cuts=$cuts", want, got.value())
                assertTrue(got.isComplete)
            }
        }
    }

    /** 真实体量：40KB 载荷按 20 字符切片。 */
    @Test
    fun `large payload survives fine-grained chunking`() {
        val code = buildString {
            append("<style>body{margin:0}</style><div id=\"app\">")
            repeat(1200) {
                append("<p>段落 ")
                append(it)
                append(" 内容 content</p>")
            }
            append("</div>")
        }
        val json = com.google.gson.Gson().toJson(
            mapOf(
                "i_have_seen_read_me" to true,
                "title" to "big_widget",
                "widget_code" to code,
                "width" to 800,
                "height" to 600
            )
        )
        val s = chunked(json, (20 until json.length step 20).toList())
        assertEquals(code, s.value())
        assertTrue(s.isComplete)
    }

    /** `\uXXXX` 被劈成两半时不得吐出半个字符。 */
    @Test
    fun `incomplete unicode escape is held until the next chunk`() {
        val s = WidgetCodeStream()
        s.feed("""{"widget_code":"abc\u4e""")
        assertEquals("abc", s.value())
        s.feed("2d")
        assertEquals("abc中", s.value())
    }

    /** 反斜杠落在片尾时不得吞掉下一片的首字符。 */
    @Test
    fun `dangling backslash is held until the next chunk`() {
        val s = WidgetCodeStream()
        s.feed("""{"widget_code":"x\""")
        assertEquals("x", s.value())
        s.feed("\"y\"}")
        assertEquals("x\"y", s.value())
        assertTrue(s.isComplete)
    }

    /** 收尾引号之后再怎么喂都不该继续增长。 */
    @Test
    fun `stops growing after the closing quote`() {
        val s = oneShot(reordered)
        assertEquals("<p>1</p>", s.value())
        s.feed(""","width":800,"height":600}""")
        assertEquals("<p>1</p>", s.value())
    }
}
