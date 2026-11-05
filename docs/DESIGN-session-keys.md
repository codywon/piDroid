# 会话正文的 key 规则（对齐 pi-web `messageKeys`）

> 结论先行：**历史消息的 id 由「会话条目」决定，绝不由「渲染窗口内的下标」决定。**
> 违反这条不变式不会渲染错，而是直接闪退。

## 为什么这是硬约束

`ChatScreen` 的正文列表是 `LazyColumn`，每一项的 `key` 就是 `ChatItem.id`
（`items(leadingSlice, key = { it.id })`）。Compose 对重复 key 抛的是
**主线程 `IllegalArgumentException`**，进程被杀 = 用户看到的「闪退」；
pi-web 在 React 里遇到重复 key 只是一条 console warning。
所以同一件事在 Android 端只能做对，没有「先这样、以后再优化」的余地。

线上两次崩溃（`server/logs/crash-2026-11-05T05-17-00-673Z.log`、
`crash-2026-11-04T15-35-43-701Z.log`）都是同一个签名：

```
java.lang.IllegalArgumentException: Key "hist_<sessionId>_78_agent" was already used.
```

## 根因

v2.0.8 及以前，历史 id 写成 `hist_<sessionId>_<chatItems.size>_<role>`
（`PiApiClient.parseRawMessagesToChatItems`）。`chatItems.size` 是**本次解析窗口内的下标**，
而窗口起点本身是会动的：

- `SessionBranchWindow.visibleTailStart(roles, tail)`：尾部预算满之后，起点随每条新消息前移；
- `floorStart = 条目树总数 - context 窗口条数`：条目树与 context 窗口两条解析路径切换时跳变；
- 压缩会新增 compaction 条目、把上下文换成摘要 —— 起点必然变化。

于是同一个下标在两次快照里指向**不同消息**。对账时
（`SessionSnapshotMerge.reconcileList`）一条按 `entryId` 配对成功、保留本地 id，
另一条没配对、按新快照自带的 id 入列 —— 两条相同 id 同时进列表，下一次组合就崩。

弱网不是异常源（网络层全部 `Result` 包裹、SSE 有退避重连），它是**触发器**：
断流 → 重连 → `reconnected_sync` → 反复重拉快照，把「同一段历史被多次以不同窗口解析」
的概率放大。这也是「网络不好时容易闪退」的真实成因。

## pi-web 的规则（照抄对象）

`hooks/useAgentSession.ts` 的 `messageKeys`：

```js
keys.push(entryId === undefined
    ? di(lastEntryId, s - messages.length)   // di = (e, t) => `live:${e ?? ""}:${t}`
    : alias.get(entryId) ?? entryId)
```

三条要点：

1. **有 entryId 就用它** —— 下标、正文都不参与；
2. **只有还没落盘的那条**（在途 / 流式）才退化成 `live:<末尾 entryId>:<距尾偏移>`。
   用「距尾偏移」而不是内容哈希，是因为流式期间正文持续增长，哈希每帧都变 → key 抖动 →
   组件重建、折叠态与滚动位置丢失；
3. **`alias` 映射** —— 同一条消息换了身份（临时身份 → 落盘 entryId）时把新 key 映射回旧 key。
   piDroid 里由 `ChatItem.streamMessageId`（`StreamingMessageMerge`）承担这件事。

## piDroid 的落地

- `data/model/HistoryItemKeys.of(sessionId, entryId, kind, fallbackSeed)`：唯一的历史 id 生成入口
  （8 处调用）。`kind` 用来区分**同一个 entry 拆出来的多个渲染单元**：`agent` / `tool_<toolCallId>` /
  `toolres_<id>` / `error` / `compaction` / `sys`；`entryId` 缺失时退化锚点是消息时间戳，仍不含下标。
- 翻页页仍套一层 `older_<cursor>_` 前缀（`PiApiClient.getOlderContextPage`），与主窗口共用同一份
  key 空间时不会撞。
- `SessionSnapshotMerge.ensureUniqueIds()`：最后一道不变式，在三个写回点各兜一次
  （快照 `synced`、对账结果、翻页 prepend）。重复时**只改 id（确定性 `dup<n>_` 前缀），不裁内容** ——
  宁可多显示一条，也不静默吞掉用户看得见的东西。`LazyColumn` 的代价是进程级崩溃，值得这一层。

## 改这块时必须遵守

- 新增任何历史消息的 `ChatItem` → 必须走 `HistoryItemKeys.of`，禁止再出现 `chatItems.size` 参与 id；
- 新增写入 `messages` / `olderMessages` 的路径 → 必须经过 `ensureUniqueIds`；
- 单测：`HistoryItemKeysTest`（跨窗口稳定）、`SessionSnapshotMergeTest`（兜底去重、内容不裁剪）；
  本地不跑 Gradle，以 CI 的 `Run Unit Tests` 为准（见 `AGENTS.md` §1.1）。
