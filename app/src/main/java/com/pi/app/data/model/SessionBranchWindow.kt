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
 * 3. 快照新不新鲜**不在这里判断**：那是服务端 `snapshotRevision` 的事（见 SessionRevision）。
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
