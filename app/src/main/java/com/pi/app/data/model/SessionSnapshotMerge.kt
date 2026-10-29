package com.pi.app.data.model

/**
 * 会话快照与本地消息列表的对账规则（纯 JVM，可单测）。
 *
 * 抽出来的唯一目的：把「服务端权威快照 vs 本地内存列表」的合并语义从 Compose/Activity
 * 里剥离成可验证的纯函数，避免树分支回退（navigate_tree / Edit from here）这类
 * 一旦算错就会把「已作废的执行过程输出」重新拼回界面的逻辑只能靠肉眼 review。
 *
 * 与 pi-web 的对应关系：
 * - `isSameChatContent` ≈ pi-web `isSameChatItem`（忽略 id / 时间戳的实质内容比较）
 * - `mergeItem` ≈ pi-web `reconcileMessages` 的单条合并（保留本地稳定 key，补齐 entryId）
 */
object SessionSnapshotMerge {

    /** 两条消息的实质内容是否一致（忽略 id 与 timestamp，用于消除轮询闪屏）。 */
    fun isSameChatContent(a: ChatItem, b: ChatItem): Boolean {
        return a.role == b.role &&
            a.content == b.content &&
            a.thinking == b.thinking &&
            a.isStreaming == b.isStreaming &&
            a.toolCall == b.toolCall &&
            a.usageSummary == b.usageSummary
    }

    /**
     * 合并单条消息：
     * 1) 内容不一致 → 以服务端为准，但角色相同时保留本地 id / timestamp（LazyColumn key 稳定）；
     * 2) 内容一致但本地缺 entryId → 静默补齐（本地乐观气泡落盘后「从此处编辑」才能用）；
     * 3) 其余情况原样返回本地对象，避免无意义重建。
     */
    fun mergeItem(localItem: ChatItem, diskItem: ChatItem): ChatItem {
        return if (!isSameChatContent(localItem, diskItem)) {
            val keepId = if (localItem.role == diskItem.role) localItem.id else diskItem.id
            val keepTs = if (localItem.role == diskItem.role) localItem.timestamp else diskItem.timestamp
            diskItem.copy(id = keepId, timestamp = keepTs)
        } else if (localItem.entryId.isNullOrBlank() && !diskItem.entryId.isNullOrBlank()) {
            localItem.copy(entryId = diskItem.entryId)
        } else {
            localItem
        }
    }
}
