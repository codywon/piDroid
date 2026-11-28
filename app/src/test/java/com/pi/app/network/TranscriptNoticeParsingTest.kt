package com.pi.app.network

import com.google.gson.JsonParser
import org.junit.Assert
import org.junit.Test

/**
 * `transcriptNotices` 的渲染文案与数字格式。
 *
 * 这些提示是 pi 自己 `notices.*` 设置打开的（默认全关），所以作用域很窄：
 * 只要保证「打开之后手机上说的话和网页端一致」，以及「没有任何提示时不要凭空造一行」。
 */
class TranscriptNoticeParsingTest {

    private fun notices(json: String): List<String> =
        TranscriptNoticeParsing.of(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `a message without notices yields nothing`() {
        Assert.assertTrue(notices("""{"role":"assistant","content":[]}""").isEmpty())
        Assert.assertTrue(notices("""{"role":"assistant","transcriptNotices":[]}""").isEmpty())
    }

    @Test
    fun `a cache miss shows tokens and the cost suffix`() {
        val lines = notices(
            """{"transcriptNotices":[{"kind":"cache_miss","missedTokens":24000,"missedCost":0.42}]}"""
        )

        Assert.assertEquals(listOf("缓存未命中：24k Token 重新计费（约 \$0.42）"), lines)
    }

    @Test
    fun `a cache miss under a cent omits the cost`() {
        val lines = notices(
            """{"transcriptNotices":[{"kind":"cache_miss","missedTokens":800,"missedCost":0.004}]}"""
        )

        Assert.assertEquals(listOf("缓存未命中：800 Token 重新计费"), lines)
    }

    @Test
    fun `an idle cache miss names the minutes`() {
        val lines = notices(
            """{"transcriptNotices":[{"kind":"cache_miss","missedTokens":1500,"missedCost":0.02,"idleMinutes":12}]}"""
        )

        Assert.assertEquals(listOf("空闲 12 分钟后缓存未命中：1.5k Token 重新计费（约 \$0.02）"), lines)
    }

    @Test
    fun `a model switch cache miss says so instead of the idle case`() {
        val lines = notices(
            """{"transcriptNotices":[{"kind":"cache_miss","missedTokens":1500,"missedCost":0.02,"idleMinutes":3,"modelChanged":true}]}"""
        )

        Assert.assertEquals(listOf("切换模型后缓存未命中：1.5k Token 重新计费（约 \$0.02）"), lines)
    }

    @Test
    fun `cache warming keeps six decimals and drops trailing zeros past the third`() {
        val lines = notices("""{"transcriptNotices":[{"kind":"cache_warm","cost":0.00123,"note":"预热"}]}""")

        Assert.assertEquals(listOf("缓存已预热（预热）：\$0.00123"), lines)
    }

    @Test
    fun `a compaction's own cost is left to the compaction card`() {
        val lines = notices(
            """{"transcriptNotices":[{"kind":"summary_cost","source":"compaction","tokens":9000,"cost":0.5}]}"""
        )

        Assert.assertTrue(lines.isEmpty())
    }

    @Test
    fun `a branch summary cost is still shown`() {
        val lines = notices(
            """{"transcriptNotices":[{"kind":"summary_cost","source":"branch_summary","tokens":9000,"cost":0.5}]}"""
        )

        Assert.assertEquals(listOf("分支摘要计费 9.0k Token（约 \$0.50）"), lines)
    }

    @Test
    fun `dropped reasoning names the provider and the reasons`() {
        val openai = notices(
            """{"transcriptNotices":[{"kind":"reasoning_dropped","provider":"openai","count":2,"reasons":["id mismatch","expired"]}]}"""
        )
        val anthropic = notices(
            """{"transcriptNotices":[{"kind":"reasoning_dropped","provider":"anthropic","count":1}]}"""
        )

        Assert.assertEquals(listOf("OpenAI 丢弃了 2 个推理项：id mismatch、expired（详情见会话文件）"), openai)
        Assert.assertEquals(listOf("Anthropic 丢弃了 1 个思考块（详情见会话文件）"), anthropic)
    }

    @Test
    fun `several notices keep their order and an unknown kind is skipped`() {
        val lines = notices(
            """
            {"transcriptNotices":[
              {"kind":"cache_warm","cost":0.0005},
              {"kind":"something-new"},
              {"kind":"cache_miss","missedTokens":3000,"missedCost":0.05}
            ]}
            """.trimIndent()
        )

        Assert.assertEquals(
            listOf("缓存已预热：\$0.0005", "缓存未命中：3.0k Token 重新计费（约 \$0.05）"),
            lines
        )
    }

    @Test
    fun `token formatting matches pi-web's thresholds`() {
        Assert.assertEquals("999", TranscriptNoticeParsing.formatTokens(999))
        Assert.assertEquals("1.5k", TranscriptNoticeParsing.formatTokens(1_500))
        Assert.assertEquals("24k", TranscriptNoticeParsing.formatTokens(24_000))
        Assert.assertEquals("1.5M", TranscriptNoticeParsing.formatTokens(1_500_000))
        Assert.assertEquals("12M", TranscriptNoticeParsing.formatTokens(12_000_000))
    }
}
