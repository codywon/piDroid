# 提示词增强的上下文供给（已实施，最小改动）

## 结论先行

「只读本会话历史够不够」——够。而且**不需要读 json 文件、不需要新接口**：
历史与工具结果本来就在客户端手上，是我们自己把它们过滤掉了。

本次只改三件事，没有加任何新架构。

## 1. 之前为什么像 demo

实测一个真实会话（230 条消息）：

```
role 分布：assistant 109 | toolResult 119 | user 1 | system 1
单条最大体积：598 KB
```

一半以上是 toolResult，而用户说「这个报错」「刚才改的那段」，信息都在里面。
旧实现的三处过滤把关键信息全丢了：

| 旧行为 | 后果 |
| --- | --- |
| 只取 `messages`（不收 `olderMessages`） | 翻页加载的更早历史进不了增强 |
| 只取 `user`/`agent` 正文，`MessageRole.TOOL` 全过滤 | 119 条工具结果一条都没给模型 |
| 助手消息额外砍到 400 字 | 上一轮的方案/代码被截断 |

## 2. 本次改动（三处）

| 位置 | 改动 |
| --- | --- |
| `ChatScreen.buildPromptEnhancementContext` | 传入 `olderMessages + messages`；新增 `MessageRole.TOOL` 分支，取 `toolName` + `command` + `output`（无 output 时退回 preview），错误收尾时标注「以错误结束」 |
| `PromptEnhancementRules` | 允许 `tool` 角色进上下文；删掉「助手只留 400 字」的特殊裁剪；条数 8→12、总量 6000→12000 字 |
| `server`（`service.mjs` / `config.js`） | 同步接受 `tool` 角色、同一口径裁剪、预算对齐 |

刻意**没做**的（避免过度设计）：

- 不读 `~/.pi/agent/sessions/*.jsonl`（客户端已有数据，多一条链路多一份风险）
- 不做 token 估算（字符数够用，且要和客户端保持一致）
- 不做相关性检索/排序（最近的工具结果就是最相关的）
- 不改提示词规则（先把信息喂够，再谈规则）
- 不注入 compactionSummary（`SYSTEM` 角色，混进 context 语义不清）

## 3. 验证

端到端实测（`codywon/deepseek-flash`，5.7s）：

输入上下文含一条编译错误工具结果
（`[bash] ./gradlew assembleDebug` → `error: unresolved reference: enhancementEnabled at ChatScreen.kt:1841`），
草稿为「这个报错怎么修，先别改代码」。

输出命中全部关键事实：

| 检查项 | 结果 |
| --- | --- |
| 含 `enhancementEnabled` | ✅ |
| 含 `ChatScreen.kt` | ✅ |
| 含 `unresolved reference` | ✅ |
| 含行号 `1841` | ✅ |
| 保留「先别改代码」 | ✅（写明「只做只读检索」「不要输出补丁」） |

服务端 85 项测试全通过（新增 2 项：工具结果进上下文、长助手回复不被砍）。

## 4. 本轮补齐的三项（原「还没做」）

| 项 | 结论 | 做法 |
| --- | --- | --- |
| 规则 pe-4 | **已做** | 【待确认】从默认改为兜底：材料里写明的事实（文件名/符号/行号/错误类型/技术栈）必须落定；篇幅按复杂度分档（120~250 / 250~450 / ≤700）；禁止任何反引号与 ** |
| 更早的历史 | **已做，但不是读 jsonl** | 注入 pi 的 compaction 摘要（`summary` 角色）。长会话被压缩后，早期约定只剩这段摘要；它本来就在内存里。读盘上的 jsonl 要额外承担路径编码、分支重建、并发写三种风险，收益相同 |
| 超时复核 | **已做** | 5 个样本实测 P50 4.1s / P90 7.7s（上下文含工具结果与摘要）→ 服务端 30s、客户端 35s |
| 读 jsonl | **不做** | 见下 |

### 为什么不做「网关按 sessionId 读 jsonl」

1. **客户端已经有数据**：`olderMessages + messages`（`/api/sessions/{id}/context` 翻页），
   加上 compaction 摘要，已能覆盖「早期决定」。
2. **真实成本在别处**：路径编码耦合（`D:\\x` → `--D--x--`）、`parentId` 树重建
   （否则读到已放弃分支）、会话正在追加写时的半行容错、以及 sessions 根目录的路径白名单。
3. **没有证据表明这是高频场景**：「引用早于已加载范围的决定」真出现时，用户上滑一次即可（现成能力）。

**触发条件**：如果后续评测里出现「模型因为缺少早期上下文而答偏」的样本，再按上述约束实现。

### pe-4 的实测效果（同一输入）

草稿「这个怎么修」，上下文只有一条 compaction 摘要（Kotlin + Compose、最小侵入、不引入新依赖）
加一条 grep 工具结果（`ChatScreen.kt:1841`）：

```
【目标】定位并修复 ChatScreen.kt 中 enhancementEnabled 分支相关的缺陷。
【已知上下文】项目是 Kotlin + Jetpack Compose 的 Android 客户端。grep 结果显示，
              enhancementEnabled 只在 ChatScreen.kt:1841 一处出现……
【待确认】当前没有提供具体故障现象。请补充：触发路径、实际表现与预期表现、报错堆栈……
```

5.4 秒返回，657 字，无反引号、无 `**`，【待确认】只有 1 处且是真正缺失的信息。
对比 pe-3 时期同样的输入：技术栈与早期约定完全看不到，【待确认】平均 2~3 处。

## 5. 业界依据（为什么不做「全量塞历史」）

- **Anthropic《Effective context engineering》**：history 是「发生过的全部」，context 是「这次真正
  发出去的 token」；进阶技术都在发更小更准的 context，塞满会降低注意力精度（Lost in the middle）。
- **AWS Well-Architected（Agentic AI Lens）**：要有显式 token 预算，context 按每次请求动态组装，
  只带与当前任务相关的部分，「而不是固定的最大载荷」。
- 因此策略是：**最近 N 条 verbatim（含工具结果）+ 单条与总量上限**，而不是读全量 jsonl。
