package com.pi.app.data.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话快照「分支窗口」规则的回归测试。
 *
 * 守护的线上现象：PC（pi-web）里一次任务有几十上百条工具调用，手机端只显示到
 * 中途某一步就再也不往后长。根因是 App 侧三条规则都按旧形状写死：
 * 嵌套树解析不出来 → 只剩 context 尾部窗口；tail 按原始条目数截窗 → 工具密集的回合被切骨；
 * 分支 leaf 滞后无感知 → 拿着滞后快照把本地更新的尾巴截掉。
 */
class SessionBranchWindowTest {

    // ── 1. 嵌套树拍平 ────────────────────────────────────────────────

    @Test
    fun `嵌套树按父在前子在后拍平`() {
        // pi-web 0.13 实测形状：[{entry:{id,parentId,type,timestamp}, children:[...]}]
        val json = JsonParser.parseString(
            """
            [
              {"entry":{"id":"a","parentId":null,"type":"message","timestamp":"t1"},
               "children":[
                 {"entry":{"id":"b","parentId":"a","type":"message","timestamp":"t2"},"children":[
                   {"entry":{"id":"c","parentId":"b","type":"model_change","timestamp":"t3"},"children":[]}
                 ]}
               ]}
            ]
            """.trimIndent()
        )
        val flat = requireNotNull(SessionBranchWindow.flattenTree(json))
        assertEquals(3, flat.size())
        assertEquals(listOf("a", "b", "c"), flat.map { it.asJsonObject.get("id").asString })
    }

    @Test
    fun `多分支树全部拍平且每棵子树独立`() {
        val json = JsonParser.parseString(
            """
            [
              {"entry":{"id":"root","parentId":null,"type":"message"},"children":[
                {"entry":{"id":"m1","parentId":"root","type":"message"},"children":[]},
                {"entry":{"id":"m2","parentId":"root","type":"message"},"children":[]}
              ]},
              {"entry":{"id":"other-root","parentId":null,"type":"session_info"},"children":[]}
            ]
            """.trimIndent()
        )
        val ids = SessionBranchWindow.flattenTree(json)!!.map { it.asJsonObject.get("id").asString }
        assertEquals(listOf("root", "m1", "m2", "other-root"), ids)
    }

    @Test
    fun `旧版 entries 与 roots 形状同样可用`() {
        val legacy = JsonParser.parseString("""{"entries":[{"id":"a","type":"message"}]}""")
        assertEquals(1, SessionBranchWindow.flattenTree(legacy)!!.size())

        val roots = JsonParser.parseString(
            """{"roots":[{"entry":{"id":"r","type":"message"},"children":[]}]}"""
        )
        assertEquals(1, SessionBranchWindow.flattenTree(roots)!!.size())
    }

    @Test
    fun `摘要树拍平后不含 message 正文（预期：调用方回落 context 窗口）`() {
        // tree=summary 只保留 {id,parentId,type,timestamp}，这里断言「不伪造内容」
        val summary = JsonParser.parseString(
            """[{"entry":{"id":"a","parentId":null,"type":"message","timestamp":"t"},"children":[]}]"""
        )
        val flat = SessionBranchWindow.flattenTree(summary)!!
        assertFalse(flat[0].asJsonObject.has("message"))
    }

    @Test
    fun `缺失或异形树返回 null 而不是空数组`() {
        assertNull(SessionBranchWindow.flattenTree(null))
        assertNull(SessionBranchWindow.flattenTree(JsonParser.parseString("null")))
        assertNull(SessionBranchWindow.flattenTree(JsonParser.parseString("\"text\"")))
        assertNull(SessionBranchWindow.flattenTree(JsonParser.parseString("[]")))
    }

    // ── 2. 可见消息预算切窗 ──────────────────────────────────────────

    @Test
    fun `工具结果不占 tail 预算`() {
        // 3 条可见消息 + 5 条 toolResult；预算 2 → 从倒数第 2 条可见消息（下标 3 的 assistant）开始保留
        val roles = listOf(
            "user", "assistant", "toolResult", "assistant", "toolResult", "assistant", "toolResult", "toolResult"
        )
        assertEquals(3, SessionBranchWindow.visibleTailStart(roles, 2))
    }

    @Test
    fun `预算够用时不截断`() {
        val roles = listOf("user", "assistant", "toolResult")
        assertEquals(0, SessionBranchWindow.visibleTailStart(roles, 60))
        assertEquals(0, SessionBranchWindow.visibleTailStart(roles, 0))
        assertEquals(0, SessionBranchWindow.visibleTailStart(emptyList(), 10))
    }

    @Test
    fun `节点缺 entry 对象时跳过它但继续走子节点`() {
        // 形状漂移的兜底：一个节点可能只有 children（压缩后合并出来的中间层）
        val json = JsonParser.parseString(
            """[{"children":[{"entry":{"id":"a","type":"message"},"children":[]}]}]"""
        )
        val ids = SessionBranchWindow.flattenTree(json)!!.map { it.asJsonObject.get("id").asString }
        assertEquals(listOf("a"), ids)
    }

    @Test
    fun `压缩里程碑占预算`() {
        val roles = listOf("compactionSummary", "user", "assistant")
        assertEquals(0, SessionBranchWindow.visibleTailStart(roles, 3))
        assertEquals(2, SessionBranchWindow.visibleTailStart(roles, 1))
    }

    @Test
    fun `分支摘要不占预算（对齐 pi 的 countsTowardTail）`() {
        // branch_summary 只是分支切换的旁注，算进预算会让窗口比 PC 端短一条
        val roles = listOf("branchSummary", "user", "assistant")
        assertEquals(1, SessionBranchWindow.visibleTailStart(roles, 2))
    }

    @Test
    fun `首个可见消息本身也要保留`() {
        // 预算 1 → 只保留最后一条可见消息及其尾部工具结果
        val roles = listOf("user", "assistant", "toolResult", "toolResult")
        assertEquals(1, SessionBranchWindow.visibleTailStart(roles, 1))
    }

    @Test
    fun `leafId 缺失时用窗口末端兑底`() {
        assertEquals("ctx-last", SessionBranchWindow.resolveActiveLeafId(null, "ctx-last"))
        assertEquals("ctx-last", SessionBranchWindow.resolveActiveLeafId("", "ctx-last"))
        assertEquals(
            "服务端下发的 leaf 优先于兑底值",
            "payload-leaf",
            SessionBranchWindow.resolveActiveLeafId("payload-leaf", "ctx-last")
        )
        assertNull("两者都缺失时就是未知，不能伪造一个叶子", SessionBranchWindow.resolveActiveLeafId(null, null))
        assertNull(SessionBranchWindow.resolveActiveLeafId("  ", ""))
    }

    // ── 3. 分支 leaf 滞后 ────────────────────────────────────────────

    @Test
    fun `context 末端对不上 leaf 判定为滞后`() {
        assertTrue(SessionBranchWindow.branchLagsBehindLeaf("leaf-1", "entry-older"))
    }

    @Test
    fun `一致或信息缺失都不判定滞后`() {
        assertFalse(SessionBranchWindow.branchLagsBehindLeaf("leaf-1", "leaf-1"))
        assertFalse(SessionBranchWindow.branchLagsBehindLeaf(null, "entry"))
        assertFalse(SessionBranchWindow.branchLagsBehindLeaf("", "entry"))
        assertFalse(SessionBranchWindow.branchLagsBehindLeaf("leaf-1", null))
        assertFalse(SessionBranchWindow.branchLagsBehindLeaf("leaf-1", ""))
    }
}
