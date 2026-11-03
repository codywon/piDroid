package com.pi.app.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement

/**
 * 会话快照「分支窗口」的三条判定规则（纯 JVM，可单测）。
 *
 * 为什么单独抽出来：这三条规则过去都写死在 PiApiClient 的 JSON 解析里，
 * 而它们恰好是「PC 上有大量执行过程、手机上看不到」的三个直接成因：
 *
 * 1. [flattenTree]：pi-web 0.13 起把会话树改成**嵌套数组** `[{entry, children}]`
 *    （响应里带 `treeFormat` 标记形态）。旧解析只认 `entries` / `tree.entries` /
 *    `messages` 三种平铺形状，遇到嵌套数组直接落空 —— `entries` 分支成了死码，
 *    App 永远只能拿到 `context.messages`（pi 上下文窗口的尾部）那一小段。
 * 2. [visibleTailStart]：pi 的 `tail` 预算**只数可见消息**（user / assistant / 压缩里程碑），
 *    不数 toolResult（见 pi-web `lib/session-reader.ts` 的 `countsTowardTail`）。
 *    按原始条目数截窗会把工具密集的回合切成一副骨架。
 * 3. [branchLagsBehindLeaf]：响应自带 `leafId`，而 `context` 窗口是从 leaf 往回走的。
 *    两者对不上就说明服务端回的是**滞后的分支**（pi-web 的 live wrapper 内存索引落后于磁盘）。
 */
object SessionBranchWindow {

    /**
     * pi 的 `countsTowardTail` 语义：只有这些角色占用 tail 预算。
     *
     * 对齐参考实现（pi-web `lib/session-reader.ts`）：compaction 条目算，`branch_summary` **不**算
     * ——后者只是分支切换的旁注，把它算进预算会让窗口比 PC 端短一条。
     * `compactionSummary` 是本项目把 compaction 条目合成出的可见消息。
     */
    fun roleCountsTowardTail(role: String?): Boolean = when (role) {
        "user", "assistant", "compactionSummary" -> true
        else -> false
    }

    /**
     * 从尾部往前数 [visibleBudget] 条可见消息，返回**保留区间的起始下标**（含）。
     *
     * - 预算 <= 0 或可见消息不足预算 → 返回 0（整体保留，绝不把窗口切空）；
     * - 否则返回那条把预算填满的可见消息所在下标，它之后的内容全部保留
     *   （工具结果、思考块不会被单独切掉一半）。
     */
    fun visibleTailStart(roles: List<String?>, visibleBudget: Int): Int {
        if (roles.isEmpty()) return 0
        if (visibleBudget <= 0) return 0
        var visible = 0
        for (i in roles.indices.reversed()) {
            if (!roleCountsTowardTail(roles[i])) continue
            visible++
            if (visible >= visibleBudget) return i
        }
        return 0
    }

    /**
     * 快照自带的 leafId 与 `context` 窗口末端对不上，说明服务端回的是**滞后的分支**。
     *
     * 任一为 null（旧版服务端不下发 / 窗口为空）时不判定为滞后：宁可少补一次请求，
     * 也不能把「叶子是 model_change 这类不产出消息的条目」误判成滞后而每次都补 `force=1`。
     */
    fun branchLagsBehindLeaf(payloadLeafId: String?, contextLastEntryId: String?): Boolean {
        if (payloadLeafId.isNullOrBlank()) return false
        if (contextLastEntryId.isNullOrBlank()) return false
        return payloadLeafId != contextLastEntryId
    }

    /**
     * 本会话的「活跃叶」：优先用服务端下发的 [payloadLeafId]，缺失时用 `context` 末条 entryId 兜底。
     *
     * 为什么需要兜底：旧版网关 / 精简快照可能不带顶层 `leafId`，而 App 用它做
     * 「分支树高亮 + `navigate_tree` 目标 + 翻页 leafId 参数」。直接为 null 会让分支树
     * 整体不高亮（内容其实完整），用户以为分支信息丢了。
     * 语义与 pi-web 一致：客户端拿到的 leaf 就是「当前上下文窗口的末端」。
     */
    fun resolveActiveLeafId(payloadLeafId: String?, contextLastEntryId: String?): String? =
        payloadLeafId?.takeIf { it.isNotBlank() } ?: contextLastEntryId?.takeIf { it.isNotBlank() }

    /**
     * 把会话树拍平成 entry 数组（父在前、子在后）。
     *
     * 兼容三种形状：
     * - `[{entry, children}]`：pi-web 0.13+ `tree=summary` / 全量树的现状；
     * - `{entries: [...]}`：旧版响应；
     * - 直接是 entry 数组，或单个 `{entry, children}` 节点。
     *
     * 注意：`tree=summary` 只保留 `{id, parentId, type, timestamp}`，**不带 message 正文**，
     * 拍平后拿不到消息是预期行为（调用方会回落到 context 窗口），不会伪造内容。
     */
    fun flattenTree(tree: JsonElement?): JsonArray? {
        if (tree == null || tree.isJsonNull) return null
        if (tree.isJsonArray) return flattenRoots(tree.asJsonArray)
        if (!tree.isJsonObject) return null
        val obj = tree.asJsonObject
        obj.get("entries")?.takeIf { it.isJsonArray }?.let { return it.asJsonArray }
        obj.get("roots")?.takeIf { it.isJsonArray }?.let { return flattenRoots(it.asJsonArray) }
        if (!obj.has("entry") && !obj.has("children")) return null
        val out = JsonArray()
        collectNode(obj, out)
        return out.takeIf { it.size() > 0 }
    }

    private fun flattenRoots(roots: JsonArray): JsonArray? {
        val out = JsonArray()
        for (el in roots) collectNode(el, out)
        return out.takeIf { it.size() > 0 }
    }

    private fun collectNode(node: JsonElement?, out: JsonArray) {
        if (node == null || !node.isJsonObject) return
        val obj = node.asJsonObject
        val entry = obj.get("entry")?.takeIf { it.isJsonObject }?.asJsonObject
        if (entry != null) {
            out.add(entry)
        } else if (obj.has("type") && obj.has("id")) {
            // 裸 entry 节点（部分调用方会把树拍平成 entry 列表再嵌套 children）
            out.add(obj)
        }
        obj.get("children")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { child ->
            collectNode(child, out)
        }
    }
}
