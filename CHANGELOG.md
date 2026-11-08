# Changelog

All notable changes to the πDroid application will be documented in this file.

> 版本标题里的日期一律为本地时间（UTC+8）。

## [2.0.37] - 2026-11-07

### ⚡ 提示词增强提速与瘦身（解决慢与超时失败）

- **对标会话标题生成与 WorkBuddy 最佳实践，纯净化上下文**：
  - 提示词增强的核心作用是消解代词（如“按上面的建议”、“刚才那个报错”），只需要最近几轮的人机对话意图与结论；
  - 彻底剔除数十 KB 的冗长终端 stdout 机器日志（如巨型编译日志、文件全文 dump），工具报错仅保留一行简要错误摘要；
  - 手机端上传请求体从 55KB 断崖式下降到 0.8KB（770 字节），彻底消除移动端与公网隧道上传延迟；
- **优化大模型首字排队延迟 (TTFT)**：
  - 输入上下文严控在 6,000 字符内（约 1,500 Token），匹配会话标题生成的极简黄金尺寸；
  - 实测各主流模型（DeepSeek / Gemini / Claude Opus / GPT-6.1）增强耗时从 15~35 秒全面降至 2~4 秒秒级响应；
- **端到端超时与安全保护**：
  - 服务端超时收敛至 30 秒，客户端 OkHttp 超时设为 35 秒，杜绝长时间假死转圈。

---

## [2.0.36] - 2026-11-07

### 🩹 修复早期历史分页丢失 entryId 与对账跨角色防护

- **补全分页历史 `attachEntryIds` 映射**：`PiApiClient.getOlderContextPage` 在解析早期记录时补齐 `attachEntryIds(arr, ctx.get("entryIds"))` 调用，根除上滑加载更早历史后消息 `entryId` 丢失、导致「从此处编辑」无法跳转的问题。
- **对账 Step 1 增加角色强校验**：`SessionSnapshotMerge.reconcileList` 的 entryId 匹配增加 `local[i].role == diskItem.role` 判定，杜绝同一 entryId 下不同角色消息发生交叉配对与顶替。
- **压缩去重算法提升为 O(1) 且区分 entryId**：`dedupCompactionCards` 引入 `seenCompactionEntryIds` HashSet，并仅在缺失 entryId 时进行跨条目同内容去重，保证具有不同 entryId 的合法历史压缩事件绝不误删。
- **解析防御性回退**：`PiApiClient` 解析 `display` 与 `customType` 时同时兼容 `rawObj` 顶层字段，`tokensBefore` 兼容读取 `details.tokensBefore`。

## [2.0.35] - 2026-11-07

### 🩹 根除会话对账导致的压缩卡片重复（四道防线）

- **修复 `ChatItem` 漏绑 `entryId`**：`PiApiClient.kt` 解析 `compactionSummary`、`custom` 与 `toolres` 时漏传了 `entryId = entryId`，导致生成的压缩卡片其 `entryId` 恒为 `null`；快照对账时因缺少 entryId 无法精确配对，在后续运行态轮询中不断被识别为未匹配项。
- **系统消息剔除出「在途推导」范围**：`SessionSnapshotMerge.reconcileList` 中 `effectiveRunning && item.entryId.isNullOrBlank()` 原本旨在保护提问/助手流式正文/工具等活跃回合未落盘产物，却因未过滤 `item.role` 把 `SYSTEM`（压缩卡片）也当成在途项带入 `inFlightItems` 重新追加到尾部，与服务端快照叠加形成翻倍。现显式限定 `item.role != MessageRole.SYSTEM`。
- **系统消息支持内容容错匹配**：若服务端条目由于历史数据或网关转换丢失 entryId，对账 Step 1 对 `MessageRole.SYSTEM` 增加同内容回退匹配，杜绝因 entryId 缺失导致的未命中。
- **列表输出增加压缩卡片去重保障**：`dedupCompactionCards` 兜底消除紧邻或完全相同的重复压缩卡片，且安全保留历史上不同轮次的合法压缩卡片。补充纯 JVM 单元测试钉住该回归向量。

## [2.0.34] - 2026-11-07

### 🩹 修复会话里反复出现「Goal」与「Continue the current task…」卡片（对标 pi-web）

- **压缩摘要被当成普通注入消息**：pi-web 服务端把 `compaction` 条目下发成 `{role:"custom", customType:"compaction", content:<摘要>, display:true, details:{tokensBefore}}`，而摘要正文恒以 Markdown 标题 `## Goal` 开头。App 只认 pi 内部的 `compactionSummary` 形态，于是这类消息掉进「通用注入消息卡片」，标题取正文首行 → 每一轮压缩后界面上就多一张只剩 “Goal” 的卡片。现在把它折回 `compactionSummary` 形态，交给既有的「📦 上下文已归档压缩（压缩前 N tokens）」卡片渲染，token 数从 `details.tokensBefore` 读取。
- **扩展的内部续跑指令被渲染出来**：`auto-continue-after-compaction` 用 `display:false` 的 custom 消息排队续跑，pi-web 的 MessageView 判据是 `rz(e){return "custom"===e.role && !e.display}`（命中直接渲染 null），App 却是「没有显式为 false 就显示」。现已改为**显式为 true 才显示**（缺字段按隐藏，对齐 JS 里 undefined 为 falsy 的语义），这条 “Continue the current task from the co…” 卡片不再出现。
- **`role:"system"` 消息整体不渲染**：pi-web 服务端构造 `context.messages` 时对 `role==="system"` 直接 `return null`，客户端也没有 system 分支。App 之前会把它当系统卡片显示，现按同一判据丢弃；`entries` 分支合成的可见 custom_message 也改折成 `role:"custom"`，两条数据源只剩一套可见性语义。
- 判据抽成纯 JVM 的 `SessionMessageVisibility`（`isVisible` / `isCompactionView`）并补 `SessionMessageVisibilityTest`，由 CI 单测锁定这两条规则。

## [2.0.33] - 2026-11-06

### 🛡️ 完善 Settle 放弃分支的运行态保护

- **避免运行中任务被 Abandon 误判完成**：`scheduleAgentCompletionSettle` 超时到达 20s 放弃点时，仅当服务端状态未明（网络断开或网关未响应）且本地无工具/重试时才执行兜底收尾；若服务端明确仍在运行（`running == true`），绝不强行触发完成提示音与空闲切回，防止任务仍在后台执行时界面误报完成。

## [2.0.32] - 2026-11-06

### 🩹 修复任务结束后底部残留「正在等待模型」幽灵状态（对标 pi-web）

- **收到 `agent_end` 立即抹除阶段**：1:1 对标 pi-web `useAgentSession.ts` 的 `setAgentPhase(null)`，收到服务端 `agent_end` 瞬间立即将 `agentPhaseLabel` 置空，不再留给 settle 防抖期间展示，彻底消除任务结束到收尾期间的视觉残留。
- **移除无脑兜底 fallback**：`ChatScreen.kt` 底部脉冲条严格对齐 `ChatWindow.tsx:1337`（`agentRunning && !hasStreamingContent && (agentPhase || isCompacting)`），彻底移除 `else -> "正在等待模型..."` 的强行 fallback；且增加 `!hasStreamingContent` 互斥保护，流式输出正文期间绝不展示等待条。
- **Settle 超时兜底收尾**：`scheduleAgentCompletionSettle` 在遇到 20s 超时（`Abandon`）时执行兜底收尾并刷新快照，防止弱网下 `isAgentBusy` 永久卡死在 `true` 导致界面假死。

## [2.0.31] - 2026-11-06

### 🩹 修复「跟随底部偶尔彻底失效」与拖动被抢

- **常驻跟随协程会被一次拖动终结**：贴底是一次 `MutatePriority.Default` 的滚动会话，用户一上手拖动（`UserInput` 优先级更高）就会抢占并取消它；`CancellationException` 逃出 `collect` 会让这个**常驻**收集器一起结束，此后本会话再也不跟随（且无任何报错）。旧实现每次增量都重启协程，所以从未暴露这个问题。现在显式区分「被用户抢占」与「自己真的被销毁」，只对后者向上抛。
- **跟随不再抢用户的手势**：`scrollToVeryBottom(abortIfUserScrolling = true)` 在 `listState.isScrollInProgress` 时主动让位（显式的「回到底部」/「从此处编辑」仍必须滚到），避免用户刚甩出去又被拽回底部。
- **压缩状态条出现/消失也会重新贴底**：`FollowSignal` 增加 `compacting`，否则压缩开始时尾部插入的一行会让视口停在半路，直到下一个 token 才补上。

## [2.0.30] - 2026-11-06

### 🩹 修复流式输出时底部闪烁（对齐 pi-web 的瞬时贴底）

- **根因**：跟随底部用的是 `animateScrollBy` 缓动动画，而跟随效果以「正文长度」为 key —— 每来一个 token 就换 key，上一轮跟随协程被 cancel、动画刚起步就被掰断重开，速度归零、永远收敛不了；正文于是每帧「长高一点 + 被拉回一点」，快输出的模型下底部那几行就在抖。pi-web 的跟随完全不带动画：每个 delta 只登记一次 `requestAnimationFrame`，同一帧的增量合并，然后在 rAF 里一次性 `scrollTo({top: scrollHeight, behavior: "auto"})`。
- **改为常驻协程 + `conflate()` + 瞬时贴底**：触发源只认内容（滚动位置不进 key，避免跟随自己触发自己、抢走用户惯性滚动），同一帧的多次变化合并成一次，延后一帧等新内容布局落地（等价 `requestAnimationFrame`），再一次性滚到真正尽头。同时移除 `followToBottomSmoothly()` 与 `ScrollFollowPolicy.needsInstantJump`（不再存在「动画/瞬移」两种贴底模式）。
- **修正滚动方向判定**：非 `reverseLayout` 列表里「首条可见项索引变大」其实是往下滚，历史实现把它当成「用户上翻」，于是跟随每跨一行（工具卡 / 过程组 / 最终答复都是独立行）就自我脱离一帧再接上 —— 表现为跟随走一下停一下。现按「索引变大 / 位移变小」严格互斥地判定，并新增 `ScrollFollowPolicy.scrollDirections` 与对应单元测试。
- **「回到底部」悬浮按钮不再闪**：显示条件从裸的 `listState.canScrollForward`（流式期间每帧翻一次，`AnimatedVisibility` 的 fadeIn/scaleIn 反复重开）改为对齐 pi-web 的 `shouldShowScrollToLatest`（距底 8px 容差），并且只在已经不再跟随时才出现。

## [2.0.29] - 2026-11-06

### 🎨 全局思考深度卡片 UI 精简与消除歧义

- **移除重复且易歧义的 Token 预算标签**：
  - 全局默认思考深度卡片顶栏右侧移除冗余的 `high · 16k 预算` 状态胶囊，避免与卡片内 7 档单选高亮状态重复，并消除“预算”一词在中文界面下被误以为资金扣费的歧义；
  - 恢复为简明的范围辅助提示 `新会话生效`，界面视觉更清爽统一。

## [2.0.28] - 2026-11-06

### ✨ 提供商与模型工作台 (Model Studio) 深度重构与交互升级

- **清爽高密度模型清单 + 按需呼出 `ModalBottomSheet` 精调抽屉**：
  - 移除原先固定堆叠在页面底部的冗长表单，主界面模型列表展示模型 ID、能力微章（思考/视觉）、显示名与 `上下文 200k / 输出 16k` 规格摘要（同名自动去重）；
  - 行内右侧内置一键 **`⚡ 测速`** 胶囊按钮（实测后直接回显毫秒级延迟）与右箭头 `›`；
  - 点击任意模型行（或点击 `+ 新增`）按需弹出 `ModalBottomSheet` 精调抽屉，内置一键 `✨ 从 models.dev 智能补全官方规格`、双列大尺寸能力开关（深度思考/多模态视觉）、容量实时换算与快捷预设胶囊（`32k / 128k / 200k / 1M`、`4k / 8k / 16k / 64k`）及可折叠的高级计费与协议覆盖区。
- **智能脏数据浮动保存栏 (Smart Dirty Bar)**：
  - 未修改配置时完全隐藏底部保存栏，释放列表空间；
  - 检测到草稿变更时底部平滑浮出带呼吸灯提示的浮动栏，支持一键「放弃修改」（还原服务端基准）或「保存并生效」。
- **一体化提供商中枢卡片与高效工具栏**：
  - 将带凭据就绪绿/黄圆点与模型计数的 Provider 横向切换栏、协议徽章、`🔑 配置/更换 Key`（含先验后存保护）、单行可复制 `Base URL` 整合进同一张紧凑卡片；
  - 将 `⚡ API 发现` 与 `+ 新增` 下沉至模型搜索框右侧，形成一体式工具行。
- **模型编辑隔离与前置校验加固**：
  - 新增 `ModelConfigUiFormatters` 与完整单元测试（`ModelConfigUiFormattersTest`），按精确下标隔离单模型修改与删除（防止改名途经已有同名 ID 时发生串台污染），并在保存前拦截空模型 ID 与重复模型 ID。

## [2.0.27] - 2026-11-06

### ⬆️ 工具链第 3 步：Gradle wrapper 8.11.1 → 8.14.6（计划收尾）

- **选 8.14.6**：8.14 线的最后一个安全补丁（2026-10-08），也是 KGP 2.2.21 官方支持的上限（Gradle 7.6.3–8.14）；AGP 8.10.1 的最低要求是 8.11.1，8.14 在其之上（允许）。升级前已确认 `https://services.gradle.org/distributions/gradle-8.14.6-bin.zip` 可解析（307 → gradle-distributions）。
- **只改 `distributionUrl`**：`gradle-wrapper.properties` 里没有 `distributionSha256Sum`，因此不需要同步校验和；`gradle-wrapper.jar` 保持原样 —— wrapper 的职责是“按 URL 下载并拉起发行版”，jar 版本与发行版版本无需一致（且按 `AGENTS.md` §1.1 本地不跑 `gradle wrapper` 去重生成）。
- **效果（含一条需纠正的判断）**：那句提示**没有**消失，只是把版本号换成了 `Gradle 8.14.6 is out of date` —— 因为 Gradle **8 整条线**已进入“仅安全修复”阶段（active support 于 2025-07-31 结束），只要还在 8.x 上，这个注解就会一直存在。真正消掉它只能升到 Gradle 9（原计划的“可选第 4 步”）。本步的实际收益是让 wrapper 落在 KGP 2.2 支持区间的上限内，避免“超出官方支持范围还能跑”的持绡偏离。
- **工具链现状（三者互相都在官方支持区间内）**：KGP **2.2.21** + AGP **8.10.1** + Gradle **8.14.6**。后续的可选第 4 步（KGP 2.3.x + AGP 8.13 + Gradle 9.x）未排期，见 `docs/PLAN-toolchain-upgrade.md`。

## [2.0.26] - 2026-11-06

### ⬆️ 工具链第 2 步：AGP 8.7.3 → 8.10.1（wrapper 仍为 8.11.1）

- **为什么是 8.10.1**：AGP 8.10 的最低 Gradle 恰好是 **8.11.1**（= 当前 wrapper），所以这一步**不需要动 wrapper**；同时它仍在 KGP 2.2.21 的 AGP 上限（8.11.1）之内。再往上到 8.11.x 会把最低 Gradle 抬到 8.13，那就得与第 3 步合并——违反“每步只动一件事”。
- **改动范围**：只改 `gradle/libs.versions.toml` 的 `agp`；Kotlin 2.2.21、Gradle 8.11.1、`compileSdk`/`targetSdk` 35、`minSdk` 26 全不变。
- **验收**：CI 的 `Run Unit Tests` + `Build Release APK` 全绿；签名前置校验（依赖 `assembleRelease` / `validateSigningRelease` 等真实任务名）仍在生效。
- **下一步**（`docs/PLAN-toolchain-upgrade.md`）：wrapper 8.11.1 → 8.14.6——这步之后 CI 那句 “Gradle 8.11.1 is out of date” 才会消失。

## [2.0.25] - 2026-11-06

### ⬆️ 工具链第 1 步：Kotlin 2.0.21 → 2.2.21（Gradle / AGP 未动）

- **为什么先升 KGP 而不先升 wrapper**：查 Kotlin 官方兼容表，**KGP 2.0.21 的上限只是 Gradle 8.8 / AGP 8.5**，而项目已经跑在 Gradle 8.11.1 + AGP 8.7.3（超出上限仍能绿，但那不是“支持”）。绑定最紧的是 KGP，先把 wrapper 推到 8.14 只会扩大偏离。升到 2.2.21 后，Gradle 7.6.3–8.14、AGP 7.3.1–8.11.1 两个区间同时容纳当前值 —— 这是项目第一次回到支持窗口内。
- **改动范围极小**：只改 `gradle/libs.versions.toml` 的 `kotlin`（`kotlin.plugin.compose` 共用同一版本号，随之升级）；Gradle 8.11.1、AGP 8.7.3、`compileSdk`/`targetSdk` 35 全部不变。
- **前置条件已就位**：2.0.21 那次把 `android.kotlinOptions` 迁到 `kotlin { compilerOptions { } }`，否则新 KGP 已经不认旧 DSL。
- **后续步骤**（写在 `docs/PLAN-toolchain-upgrade.md`）：AGP 8.7.3 → 8.10.x（最低 Gradle 恰好 8.11.1）→ wrapper → 8.14.6。每步一个补丁版本、一次 CI。

## [2.0.24] - 2026-11-06

### 🔧 修复 2.0.23 的单测源集编译失败，内容与 2.0.23 一致

- **失败一**：`SessionRelationVisibilityTest` 还在断已删除的 `Session.cost`（`Unresolved reference 'cost'`）。移除该断言，并把 fixture 里那个已经不会被任何字段接收的 `"cost":0.42` 一并删掉 —— 真实列表响应（实测 484 条）本来就没有这个 key。
- **失败二**：`SessionSaverTest` 里 `SessionSaver.restore(emptyList())` 推不出元素类型（`restore` 的形参是 `Any`）。改成 `emptyList<String>()` / `listOf<String>(…)`。
- **发版说明**：`v2.0.23` 已推送且有 CI 记录，按 SOP 不复用，递增到 `2.0.24`（versionCode 222）；2.0.23 从未发布。

本版完整内容（同 2.0.23）：删除 `Session` 上三个从未被列表响应赋值的字段（`tokenUsage` / `cost` / `totalActiveMs`），`SessionSaver` 的槽位同步重排；新增 `SessionSaverTest` 钉住位置化存档的线格式；`docs/PLAN-toolchain-upgrade.md` 记录 Gradle wrapper → AGP 的分步升级计划。

## [2.0.23] - 2026-11-06

### 🧽 删掉会话模型里三个“永远为 0”的字段（与 pi-web 的列表契约对齐）

- **依据（实测，不是推测）**：拉本机网关的 `GET /api/sessions`（484 条会话），单个对象的 key 只有
  `id / path / cwd / projectRoot / projectKey / branch / firstMessage / messageCount / created / modified / transient`（以及家族的 `relation`）。
  `tokenUsage` / `cost` / `totalActiveMs` **一个都没有** —— 上游把它们放在
  `GET /api/sessions/{id}` 的 `stats`（会话信息面板）与 `POST /api/sessions/stats`（会话家族汇总）里，不在列表里。
- **之前的状态**：`Session` 里挂了这三个 `@SerializedName` 字段，永远是默认值 0，而卡片上那行
  「耗时 · token · 费用」曾把它们当真实读数展示（那些数字其实来自 stats 接口）。卡片元信息下线后，
  它们变成纯死负载：解析、落盘、从不展示。
- **现在**：三个字段连同 `SessionSaver` 的两个槽位一起删除（位置化编码的下标同步重排），
  并在 `Session` 的 KDoc 里写清列表契约与这三个数的真实出处，防止以后把“列表不发的字段”再加回来。
- **测试**：新增 `SessionSaverTest`（四个用例）——位置化存档最容易静默坏掉，把
  `active / awaitingInput / isRunning / isWorktree` 的槽位与默认值钉死；另覆盖空存档与截断存档。

### 🗺️ 工具链升级排期（仅文档，不改代码）

- CI 每次提示 Gradle 8.11.1 已过期。新增 `docs/PLAN-toolchain-upgrade.md`，把 wrapper → AGP 的升级
  拆成分步计划（每步一个补丁版本、一次 CI），并写明禁止本地编译、不复用 tag、不动签名材料等约束。

## [2.0.22] - 2026-11-06

### 🔧 修复 2.0.21 的编译失败（格式化函数放错了作用域），内容与 2.0.21 一致

- **失败原因**：`TokenStats.formatCacheHitRate` 被写在了 **class 体**里（紧跟 `cacheHitRate` 属性），而 HUD 与单测都是按 `TokenStats.formatCacheHitRate(…)` 的**伴生对象**方式调用 —— `:app:compileDebugKotlin` 报 `Unresolved reference 'formatCacheHitRate'`，2.0.21 未产出安装包。
- **修法**：把该函数移入 `companion object`；`cacheHitRateAvailable` 读的是实例字段，保留在 class 体（它是 `val … get()`，调用点 `stats.cacheHitRateAvailable` 不变）。逻辑零改动。
- **发版说明**：按发版 SOP 不复用已推送过的 `v2.0.21` tag，递增到 `2.0.22`（versionCode 220）；2.0.21 从未发布，用户端只会看到 2.0.22。

本版完整内容（同 2.0.21）：

- **Token HUD 对齐 pi-web 会话信息面板**：缓存率卡片改为条件显示（`cacheRead + cacheWrite > 0` 且分母 > 0），从未用过前缀缓存的会话不再摆一个 `0.0%`；副标题由「缓存命中: cacheRead 单值」改为上游口径的「缓存读取 / 缓存写入」两个独立读数；成本行同样按 `cost > 0` 条件显示，并用 `Locale.US` 固定点号小数（上游 `toFixed(4)` 的口径，德语环境不再显示 `0,0426$`）；命中率文案收敛为 `TokenStats.formatCacheHitRate()` 一份实现，HUD 与单测共用。
- **工程卫生**：`android.kotlinOptions` → 顶层 `kotlin { compilerOptions { } }`（KGP 2.0 起前者已废弃）；commonmark 四个坐标移入 `gradle/libs.versions.toml`；发布签名前置校验补上 `signReleaseBundle` / `packageReleaseBundle`。

## [2.0.21] - 2026-11-06

### 🎛️ Token HUD 对齐 pi-web 会话信息面板（显示条件 / 两个缓存计数 / 格式化）

- **缓存率卡片改为条件显示**：上游 `components/AppShell.tsx` 把这一行包在 `cacheRead + cacheWrite > 0 && cacheRead + cacheWrite + input > 0` 里。以前我们无条件渲染，从未走过前缀缓存的会话也摆一个毫无意义的 `0.0%`。
- **副标题改成两个独立读数**：由「缓存命中: cacheRead 单值」改为上游口径的「缓存读取 / 缓存写入」（各自 `> 0` 才出现）。把 `cacheRead` 一个数当成“缓存命中总数”是不对的。
- **成本行同样按上游 `cost > 0` 条件显示**，并改用 `Locale.US`：上游是 `$${cost.toFixed(4)}`，而 `toFixed()` 永远输出点号小数；旧代码跟随系统 Locale，德语等地区会显示成 `0,0426$`。
- **命中率文案收敛为一份实现**：新增 `TokenStats.formatCacheHitRate()`，HUD 与单测共用（此前测试里是格式串的副本，只能证明“测试和自己一致”）；`TokenStats.cacheHitRateAvailable` 把上游的显示条件变成可单测的纯逻辑。

### 🧹 工程卫生（不改运行行为）

- `android.kotlinOptions` 迁移到顶层 `kotlin { compilerOptions { } }`：KGP 2.0（本项目 2.0.21）起前者已废弃，现在只出 warning，将来会变成 error。`jvmTarget` 仍为 17，与 `compileOptions` 一致。
- commonmark 四个坐标移入 `gradle/libs.versions.toml`（此前只有它们硬编码版本，升级要改四处）。
- 发布签名前置校验补上 `signReleaseBundle` / `packageReleaseBundle`：只出 APK 时用不到，但走 AAB 时缺 secret 会退化成 AGP 的原始报错。
- CHANGELOG：版本标题的日期基准已在文件头注明（本地时间 UTC+8），最近四版的弯引号统一为「」。

## [2.0.20] - 2026-11-06

### 🔍 缓存命中率的「累计口径」改成整体回退（修 2.0.19 的边界）

- **问题**：2.0.19 把三个数统一到了会话累计，但回退判据是**逐字段**的（`if (inTokens > 0L) … else 单轮 input`）。整段 prompt 全命中缓存时 `stats.tokens.input` 合法地为 0，此时分母会把单轮 input 拼回来 —— 与本次修掉的「混口径」是同一类。
- **修复**：新增纯函数 `TokenStats.resolveSummaryTokens`，判据换成「网关到底有没有下发 `stats.tokens`」这一个布尔：有就整体用累计（即使 input 为 0），没有才 input/output 一起退回最后一条 assistant 消息的 usage。另外对负值做了 `coerceAtLeast(0)` —— 负 token 无论来自哪个源都不该进分母。
- **测试**：`TokenStatsCacheRateTest` 新增 4 条（累计为 0 不被单轮顶替、累计缺失时整体回退、回退后的值确实参与比值、负值不泄漏）。

### 🧹 文档与发版流程

- `CHANGELOG` 的 `2.0.17` 段落标注「未发布（构建失败，内容随 2.0.18 出货）」，避免把从未存在的版本读成已发布版本。
- 发版流水线新增前置门禁：tag（或手动触发输入）与 `app/build.gradle.kts` 的 `versionName` 不一致、或 `CHANGELOG` 缺对应段落时，几秒内直接失败 —— 否则会出现「Release 叫 vX.Y.Z、装完仍提示有新版本」的死循环。

## [2.0.19] - 2026-11-06

### 📊 Token HUD 缓存命中率对齐 pi-web（不再永远顶格 100.0%）

- **根因**：HUD 的命中率分子取会话**累计** `cacheRead`，分母却用最后一条 assistant 消息的**单轮** `input`，且漏掉了 `cacheWrite`。两个口径一混，「累计命中 ÷ 单轮输入」在任何带前缀缓存的会话上都会算成 99.99% → 四舍五入后永远显示 100.0%（实测 27.5 M cacheRead 配 169 input），这个数字既不真实也不携带信息。
- **修复**：口径与上游 `session.cacheHitRate` 完全一致 —— `cacheRead / (cacheRead + cacheWrite + input)`，三个数**全部取自会话累计**；仅当网关不返回 `stats.tokens`（累计缺席）时才退回最后一条 assistant 消息的 usage，那是「有真实来源的退路」而不是编造。上下文占用仍单独按最后一条消息计算，不受影响。
- **测试**：新增 `TokenStatsCacheRateTest` —— 分母包含 `cacheWrite`、累计口径不顶格、混合口径必然 100.0% 的证据固化、零用量不除零。

## [2.0.18] - 2026-11-05

### 🔧 修复 2.0.17 的构建失败（局部函数前向引用），内容与 2.0.17 一致

- **失败原因**：`loadSessions` 调用了在它**后面**才声明的局部函数 `refreshSessionUiState()`，Kotlin 的局部函数必须先声明后使用（这一点与成员函数不同），因此 `:app:compileDebugKotlin` 报 `Unresolved reference`，2.0.17 未产出安装包。
- **修法**：把该函数整段上提到 `loadSessions` 之前，逻辑零改动；行为与 2.0.17 完全相同。
- **发版说明**：按发版 SOP 不复用已推送过的 `v2.0.17` tag，直接递增到 `2.0.18`（当时的 versionCode 216）重跑流水线；2.0.17 从未发布，用户端只会看到 2.0.18。

本版包含的完整内容（同 2.0.17）：

- **右上角不再转三十多秒**：转圈只覆盖 `/api/sessions` 本身，回包即复位（取消/失败也走 `finally`）；`ui-state` 降为后台附属请求；从会话返回列表改为静默对账。根据「完全对齐 pi-web」的结论，`POST /api/sessions/stats`（服务端按会话文件逐个解析，484 条会话实测 ≈ 31 秒）在客户端整条下线：`getBatchSessionStats`、卡片 `stats` 参数、`SessionStatsFormatting` 及对应单测一并移除。上游该接口只服务于会话家族（`[rootSessionId, ...subagents]`），侧栏会话行从不显示 token / 费用 / 耗时。
- **冷启动不再固定落回 termius**：1.8.88 的就绪门禁写的是 `isLoading && sessions.isEmpty()`，而冷启动走静默拉取、`isLoading` 恒为 false，门禁从未生效。现在以「首份完整会话快照是否到达」为判据（`sessionsSnapshotLoaded` → `isInitialSnapshotReady`），并把选区仲裁抽成纯函数 `decideWorkspaceSelection`（`Defer / Keep / ApplyHint / Fallback`）：数据未就绪一律 `Defer`，被动兜底仍然只挪内存指针、不写盘。
- **会话卡右侧槽位对齐 pi-web**：`运行中 / 等待确认 / 未读` 保持原样，其余显示相对时间（`Rg()` 口径：刚刚 / N分 / N小时 / N天 / N周 / N个月 / N年），置顶区显示所属项目名；底部「耗时 · token · 费用」整行移除（同时消掉 UTC ISO 按本地时区格式化造成的「慢 8 小时」类时区问题）。
- **顶栏移除扫码图标**：未连接时已有横幅主按钮、已连接后由「系统设置中心」承担，避免列表页误触。
- **测试**：新增 `SessionTimeFormattingTest`（相对时间全部分档边界、未来时间、非法输入、UTC ISO 不漂移）；`WorkspacePathsTest` 新增 7 条选区仲裁回归（含「首帧只有 termius 时必须 Defer」）；移除已下线功能的 `SessionStatsFormattingTest`。

## [2.0.17] - 2026-11-05

> ⚠️ **未发布**：构建在 `Run Unit Tests` 阶段失败（`Unresolved reference 'refreshSessionUiState'`），未产出安装包、未创建 Release。下列内容实际随 **2.0.18** 出货。

### ⏱️ 从会话返回工作区，右上角不再转三十多秒

- **根因**：`loadSessions` 把 `POST /api/sessions/stats` 挂在转圈协程的 `finally` 之前，而这是服务端按会话文件逐个算 token / 耗时的重活 —— 本机 484 条会话分成 3 个 200 条分片，实测 20.3s + 10.1s + 0.6s ≈ **31 秒**。列表其实早就渲染完了，图标却要等统计算完才停；而「从会话返回」还走非静默刷新，所以每次都必现。
- **修复**：转圈只覆盖 `/api/sessions` 本身，回包即复位（取消 / 失败同样走 `finally`，闸门不会卡死）；`ui-state` 降为后台附属请求；返回列表改为静默对账。
- **完全对齐 pi-web**：核对上游客户端包后确认，该统计接口只服务于**会话家族**（`[rootSessionId, ...subagents]`，通常 1 个 id），侧栏会话行从不显示 token / 费用 / 耗时。因此本次把客户端整条链路删除：`getBatchSessionStats`、卡片的 `stats` 参数、`SessionStatsFormatting` 与对应单测一并下线。列表页从此不再发起这个请求。

### 🧭 冷启动不再固定落回 termius（1.8.88 没修干净的那一半）

- **根因**：1.8.88 的就绪门禁写的是 `isLoading && sessions.isEmpty()`，而冷启动走的是静默拉取（`loadSessions(silent = true)`），`isLoading` 恒为 false —— 门禁在冷启动从未生效。首帧只拿到离线自定义目录（历史上有 `D:\myscript\termius`），「上次停留的工作区」被判「已失效」，兜底把**内存选区指针**钉在 termius；之后真实数据到达也不会纠偏，因为兜底分支只在「当前指针无效」时才动手。磁盘里的记忆一直是对的，被钉死的是界面。
- **修复**：判据换成「首份**完整**会话快照是否到达」（`sessionsSnapshotLoaded` → `isInitialSnapshotReady`），并把选区仲裁抽成纯函数 `decideWorkspaceSelection`（`Defer / Keep / ApplyHint / Fallback`）：数据未就绪一律 `Defer`，被动兜底仍然只挪内存指针、绝不回写磁盘。

### 🕒 会话卡右侧改为相对时间（对齐 pi-web 右侧槽位）

- 时间口径照抄上游 `Rg()`（zh-CN 单位表）：刚刚 / N分 / N小时 / N天 / N周 / N个月 / N年；置顶区按上游口径显示所属项目名。顺带消掉「UTC ISO 串按本地时区格式化」造成的「比 pi-web 慢 8 小时」类时区问题。
- 卡片底部那行「耗时 · token · 费用」整行移除（上游侧栏会话行没有这些读数，实测它们才是那 31 秒的来源）。
- 顶栏移除扫码图标（未连接时已有横幅主按钮、已连接后由「系统设置中心」承担），避免列表页误触。

### 🧪 测试

- 新增 `SessionTimeFormattingTest`：相对时间全部分档边界、未来时间归「刚刚」、非法输入空串、UTC ISO 不漂移。
- `WorkspacePathsTest` 新增 7 条选区仲裁回归用例，第一条即「首帧只有 termius 时必须 Defer，不得钉死」。
- 移除 `SessionStatsFormattingTest`（功能整体下线，不是改断言迁就实现）。

## [2.0.16] - 2026-11-05

### 📄 PDF 双指缩放极度卡顿修复：手势协程稳定化 + Draw 阶段零重组

- **根因修复**：`PdfDocumentView` 的 `pointerInput` 误将 `scale` 变量作为重启 key，导致双指放大过程中每秒几十次微小缩放都会强制中断并重启整个手势协程，造成手势检测器状态丢失与严重卡顿跳屏。现解除 `scale` key 绑定，协程平稳常驻；
- **消除全量重组**：将 `Modifier.graphicsLayer(scaleX = scale, ...)` 传值形式重构成 Lambda 块 `graphicsLayer { scaleX = scale; ... }`，将所有矩阵计算推迟至绘制（Draw）阶段执行，彻底消除放大时的界面全树 Recomposition；
- **手势与交互对标**：支持 0.75x ~ 6x 平滑自由缩放、视口边界平移限制与双击 1x/2.5x 切换，放大比例大于 1.15x 时在右下角提供便捷的「XX% · 点击复位」胶囊（与 `ZoomableImageCanvas` 交互完全统一）。

### 🖼️ 图片全屏预览对标 pi-web：4K 原画级 1:1 输出 + 顶栏尺寸呈现

- **杜绝放大模糊**：旧实现将图片解码目标硬编码为 `maxDimension = 2048`，导致常见的 2K/4K 电脑截图与架构图被强制降采样丢弃大量像素。现新增 `FULLSCREEN_DECODE_TARGET_PX = 4096`，4K UHD（3840×2160）以内图片保持 1:1 原画质解码进内存，双指放大后小字、线条分毫毕现；
- **防 OOM 自适应兜底**：仅针对大于 4096px 的极端超长/超大长图按 2 的幂次安全降采样，Native 内存绝对受控；
- **对齐 pi-web 尺寸展示**：统一通过 `decodeFullImagePreview` 提取图片的真实物理分辨率（如 `3840×2160`），并格式化呈现到顶栏（例如 `1.4 MB · 3840×2160 · 2026-03-01`），完全对标 pi-web 的 `naturalSize` 展示体验。

## [2.0.15] - 2026-11-05

### 📉 列表加载失败不再显示成“一条都没有”（对标 pi-web 的失败呈现）

2.0.11 删掉了假数据兜底，但失败时界面只会留下一个空列表 —— 用户读到的是「一个技能也没装 / 一个模型都没有」，真相却是请求没成功。这次按 pi-web 的机制补齐：**错误带状态码报出来，并给一个就地重试**。

- **错误带上 HTTP 状态码**：`getSkills` / `getPlugins` 现在记录最后一次非 2xx 的响应码，失败返回 `HTTP 502` / `网络错误`（pi-web 的文案就是 `Failed to load models (HTTP 502)`），而不是笼统的“加载失败”。
- **技能 / 插件 Tab**：列表为空且上次加载失败时，空态提示改成「…加载失败（HTTP 502）· 点此重试」，点击重新拉取。已成功加载过则保留上一次结果，不被一次抖动清空。
- **模型选择弹层**（聊天页最常用的入口）：列表为空且失败时，在筛选栏位置显示「模型列表加载失败（HTTP 502）」+ 就地「重试」，而不是一片空白。
- **保留“最后一次成功的数据”**：失败只置错误标记，不清空已有列表 —— 与 pi-web 的缓存语义一致：抖动一次不该把用户刚看到的内容抹掉。
- 设置页的模型管理 Tab（`GlobalModelsConfigTab`）本来就有自己的错误提示（Toast + 本地缓存兜底），未改动。

## [2.0.14] - 2026-11-05

### 🔐 不可信 SVG：用 CSP nonce 当边界，而不是“更努力地写正则”

- **为什么要换**：旧实现用三条正则剥 `<script>` 与 `on*`。这类过滤天然可绕（`<svg/onload=…>`、大小写/编码变形、`javascript:` 链接），把正则写得更全是无止境的军备竞赛，且改错一次就是漏洞。
- **新边界**：每次渲染生成一个随机 nonce，页面带 `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; img-src data: blob:; script-src 'nonce-<random>'`。浏览器直接拒绝执行一切没有本次 nonce 的脚本与内联事件处理器 —— 嵌入内容无法猜到随机 nonce，无论怎么变形都跑不起来。正则保留，但只当“去掉噪声”的预处理，注释里明确它**不是**安全边界。
- **顺带修掉一个真问题**：这个 WebView 是唯一没设 `WebViewClient` 的（`GenerativeUi`、`KaTeX` 都有）。不设时 SVG 里的 `<a href>` 会直接在查看器内导航 —— 点一下图里的链接，整个查看器就变成一个任意网页。现在一律拦截（这个 WebView 只用来画图，没有合法的跳转目的地）。
- 缩放 JS 保留（去掉它才是真风险：`builtInZoomControls` 的体验与本实现的“双击放大到落点/拖动带边界”并不等价）。CSP 只放行带 nonce 的那一段我们自己的脚本；徽标原本的 `onclick=` 被 CSP 拦掉，已改成 `addEventListener` 绑定。

### 📱 边缘返回手势：Android 13+ 交给系统，不再抢同一块区域

清单已开启 `enableOnBackInvokedCallback`，系统在左边缘有带预测动效的返回手势。自定义的 28dp 边缘右滑用 `PointerEventPass.Initial` 抢先消费，结果是“滑到一半卡住”或一次手势触发两次返回。三处调用方（`FilesScreen` / `ArtifactViewerFullScreen` / `GitChangesFullScreen`）都已注册 `BackHandler`，系统回退走同一处理函数 —— 所以在 API 33+ 直接停用自定义手势，功能不减，只是不再和系统抢。

### 🧵 Worktree 弹窗：去掉同方向嵌套滚动

`ModalBottomSheet` 里原本内嵌一个 `heightIn(max = 280.dp)` 的 `LazyColumn`：滑到列表底部再继续拖，手势会被外层解释成“下拉收起”，手感是列表抖一下、弹窗想关。改为整个弹窗只有一个滚动容器（外层 `verticalScroll` + 普通 `Column` 铺项）。worktree 数量等于真实分支检出数，通常个位数，不需要虚拟化。

## [2.0.13] - 2026-11-05

### ⚙️ 热路径正则常量：不再每次调用都 `Pattern.compile`

Kotlin 的 `Regex("…")` 每次求值都会新建一个 `Pattern`，而这些模式全是**固定字符集**、与输入无关。全仓扫描出 16 处内联写法（3 处本来就在 `val` 列表里），本次把其余 13 处提到常量。

- **真正的热路径**：`Models.kt` 的 `SessionSearchHit.snippet` 是 getter，会话列表每滑过一个 item 读一次；`ChatScreen` 的系统状态卡片标题与思考预览在**流式期间每个 token** 都会重算（`remember(content)` 的 key 就是 content）；`MainActivity` 的自动标题在每个新会话开场消息上跑一次。
- 其余（`SessionExporter`、`FileDownloader`、`PiApiClient` 的缓存文件名、`GenerativeUi` 的微组件文件名、`MediaPreviewComponents` 的 SVG 清洗）调用频次不高，但同样没有理由把编译产物写成散落的字面量 —— 尤其 SVG 那几个是安全相关规则，集中在一处才好审。
- **行为零变化**：每个常量字面量与被替换的内联字面量逐字节一致，已用 diff 逐条核对。

### 🛡️ 加固文件名净化：`..` 不再原样放行

`FileDownloader.sanitizeFileName` 只替换 `\ / : * ? " < > |`，所以 `..`、`.`、`...` 会被原样保留。在 Android 9 及以下（走 `File(targetDir, safeName)`）`..` 会解析成**父目录**：写盘只能得到一句语焉不明的 IOException，返回给用户的保存路径里还会出现形似路径穿越的 `..`。现在只由点组成的名字直接退回兜底名，含点的正常文件名（`v1.2.pdf`、`..hidden`）不受影响。`FileNamingTest` 补了两组用例钉住这条边界，原有 7 条断言全部保持。

## [2.0.12] - 2026-11-05

### ⚡ 流式每个 token 不再连带整个聊天页重组

- **问题**：`MainAppNav` 把 `lastSseActivityAt`（SSE 最后事件时间戳）当**值**传给 `ChatScreen`。这个值在流式期间**每个 token 都会变**，于是 MainAppNav 重组 → 参数变更 → 4200 行的 `ChatScreen` 全屏大重组，每来一个 SSE 事件就发生一次。而它真正的用处只有一个：打开 Token 监控弹窗时显示「事件 Ns 前」。
- **Fix**：改成传 lambda（`streamLastEventAtMs: () -> Long`），读取推迟到弹窗内部的 `SyncHealthRow` —— 那里本来就有 1s ticker 负责让相对时间持续走动。弹窗没开时，这个状态变化不再触发任何重组。
- 顺带把 `conversation = olderMessages + messages` 改成「没翻过页就直接复用 `messages`」：这行**每个重组都会走**，而绝大多数会话从未加载过更早记录，原来的写法在流式期间就是每个 token 新建一个 ArrayList 抄一遍全部引用（`+` 不共享底层数组）。
- 未动 `lastSnapshotAtMs`：它只在快照成功时变化（每分钟几次量级），不值得为它增加一个参数层。


## [2.0.11] - 2026-11-05

### 🚫 不再用假数据兜底：网络失败就如实说失败

`AGENTS.md` 要求「对齐 pi-web 的真实状态」，但这几处一直在 App 侧编造内容，而且伪装成 `Result.success`，让真实故障彻底不可见。

- **技能 / 插件**：未配置网关时返回写死的「hindsight-coding-agent / context-mode / terminal-controller / ui-plugin-navigation」四个技能（每个还带着编造的 SKILL.md 正文）与两个插件；接口挂掉时同样回退到这四份假数据。现在未配置网关 → `emptyList()`（本来就没有可列的东西），接口失败 → `Result.failure`。
- **技能正文**：`getSkillContent` 读不到内容时会生成一份「看起来像真的」SKILL.md 模板。这不只是显示问题——用户在 App 里点保存，假模板会**真的写回磁盘**，把编造内容变成真文件。现在读不到就报错。
- **模型目录**：`getDynamicModels()` 原本无条件 `getOrElse { 内置目录 }` 再包成成功，于是隧道 502 / pi-web 没起来 / 密钥失效全部显示成「加载成功」，界面上只剩一份和 PC 无关的模型列表。现在**只有未配置网关**（首次启动、刚断开）才用内置目录，连上过网关后失败一律返回 failure。`/api/models` 成功但目录为空时也不再塞内置模型顶替。
- 内置目录本身保留（首次安装时模型选择器需要「总有东西可选」），但收窄成唯一一个触发条件。

### 🧹 死代码与权限清理

- 删除 `voice/VoiceInputManager.kt`、`voice/SmartTtsManager.kt`（全仓零引用，从未被任何界面调用）。
- 移除清单里的 `RECORD_AUDIO` 声明：声明了危险权限却无对应功能，是应用市场合规审查的直接失分项。相机权限保留（扫码在用）。

### 🔢 上下文窗口默认值收口

`200000L` 此前散落在 6 个文件 11 处（data class 默认值、快照解析兜底、模型覆盖读取、目录合并、内置目录、设置页输入框初始值与解析兜底）。新增 `ModelDefaults.DEFAULT_CONTEXT_WINDOW` / `DEFAULT_MAX_OUTPUT_TOKENS` 统一，避免「界面初始值、解析兜底、目录合并各说各话」导致同一设备上上下文上限时大时小。

### 📐 文案规范对齐 `docs/DESIGN-labels.md`

- `SettingsComponents` 的 subtitle `maxLines` 由 2 改回 **1**（§4 硬约束；允许折行等于默许写长文案）。
- 4 处超 18 字的 subtitle 收敛：「删除所有历史会话（正在运行的会话会自动保留）」→「正在运行的会话不受影响」；「ghfast.top / ghproxy.net / gh-proxy.com 中转下载」→「经第三方镜像中转下载」（域名清单本来就在 `ⓘ` 弹窗里）；「点右上角添加，或先在电脑端的模型配置里声明」→「点右上角添加」；「支持 npm: / git: / 本地路径安装」→「npm: / git: / 本地路径」（同时去掉禁用语「支持…」）。
- 修掉 `DESIGN-labels.md` 自身的矛盾：§2 把「…会话会自动保留」当**允许范例**，§4 又把「会自动」列为禁用语。现在 §2 范例改成合规写法，并在 §4 注明「会自动」的替身是「…不受影响」，两节口径统一。


## [2.0.10] - 2026-11-05

### 🐛 工具跑完还在转圈、中间正文不刷新：回合大纲不再用那套伪缓存键

- **现象**：① 工具已经执行完，卡片上的转圈一直不停，要等新消息入队才刷过来；② 模型把正文回填给前面（非尾部）的 AGENT 气泡时，界面上的旧正文被“吃掉”，也是延后一阵才闪出来。
- **根因**：`ChatScreen` 把整场会话的回合大纲包在 `remember` 里，key 是 `conversation.size` / 首尾两条 id / 尾部 `content` 与 `thinking` 长度 / `isAgentBusy`。而工具结果回来走的是 `messages[idx] = existing.copy(toolCall = …)`：列表长度不变、首尾 id 不变，TOOL 项的 `content` 恒为 `""`（长度恒 0）—— 六个 key 一个都不动，大纲于是继续返回旧快照。非尾部正文回填同理。
- **Fix**：把大纲构建抽成纯 JVM 的 `buildTurnOutlines()`（`ui/chat/TurnOutlineBuilder.kt`）并取消那套启发式缓存，改为每次基于传入列表重算。`messages` 是 `SnapshotStateList`，写入粒度本就是整个列表 —— 任何一处就地更新都会让 `ChatScreen` 重组，所以旧 key 在流式期间根本不可能命中（尾部每个 token 都在变），它的收益只发生在“重组但消息未变”时。
- **代价与取舍**：重组时会多走一次 O(n)（与既有的 `olderMessages + messages` 拷贝同级）；换来的是**构造上正确**。`conversation` 那次拷贝留到后续统一优化（两者的正确解法是同一个会话版本号）。
- **单测**：`TurnOutlineBuilderTest` 直接钉住两个回归 —— 「就地替换工具结果必须进大纲」与「非尾部正文回填必须可见」；另覆盖最终答复提升、未答复不拆、`visualize_read_me` 不计入工具次数、回合编号与空会话。

### 🧮 上下文 Token 不再取模造假，超窗就如实说超窗

- **根因**：`PiApiClient` 在「会话累计输入 > 模型窗口」时返回 `inTokens % 80000`（下限 24000）。真实的 340K 会被显示成 20K，用户既看不到已经超窗，也无从排查“上下文怎么爆的”。
- **Fix**：只保留两个真实来源 —— 最后一条 assistant 消息的 usage（`input + cacheRead`，即此刻上下文大小）为正时用它；否则退回会话累计输入（`stats.tokens.input`）。两者都是服务端实际给出的数值，不再做任何加工。
- **UI 口径**：超窗时不再写「100%」（那和“刚好装满”无法区分）—— HUD 显示 `340 K / 200 K (已超窗)`，底部指标栏显示 `100%+`。进度条本身仍夹在 0~1，否则会画出界。
- **单测**：`TokenStatsContextTest` 覆盖权威值优先、累计值兜底、脏数据夹零、超窗边界（`>` 而非 `>=`）、窗口未知不告警、文案格式与超窗文案。


## [2.0.9] - 2026-11-05

### 💥 修掉「跑一会就闪退」：历史消息 id 改为对齐 pi-web `messageKeys` 的条目身份

线上两轮崩溃日志（`server/logs/crash-2026-11-05T05-17-00-673Z.log`、`crash-2026-11-04T15-35-43-701Z.log`）都是同一个签名：

```
java.lang.IllegalArgumentException: Key "hist_<sessionId>_78_agent" was already used.
  at … android.os.Looper.loop  (thread=main)
```

- **根因**：历史消息 id 是 `hist_<sessionId>_<窗口内下标>_<role>`，而渲染窗口的起点会随会话增长前移（`SessionBranchWindow.visibleTailStart` 与 `floorStart`），压缩、自动重试换手、entries 树 / context 窗口两条解析路径切换都会让它跳变。于是同一个下标在两次快照里指向不同消息 → 对账（`SessionSnapshotMerge.reconcileList`）后一条配对成功保留本地 id、一条未配对按新快照自带 id 入列 → 两条相同 id → `LazyColumn` 抛主线程致命异常，进程被杀。弱网只是触发器：断流重连 → `reconnected_sync` → 反复重拉快照，把「同一段历史被多次以不同窗口解析」的概率放大。
- **对齐上游**：pi-web `useAgentSession` 的 `messageKeys` 是「条目有 id 就用它，只有还没落盘的那条才退化成 `live:<末尾 entryId>:<距尾偏移>`」——身份是**会话条目**，不是窗口位置。新增纯 JVM `HistoryItemKeys`（`data/model/HistoryItemKeys.kt`）落实这条规则，8 处历史 id 生成全部改用它；`entryId` 缺失时退化锚点是消息时间戳，仍然不含下标。工具卡与正文气泡同属一个 assistant entry，用 `kind` 区分（`agent` / `tool_<toolCallId>` / `toolres_<id>` / `error` / `compaction` / `sys`）。
- **为什么不采用内容指纹**：流式期间正文持续增长，哈希每帧都变 → key 抖动 → 气泡重建、工具卡折叠态与滚动位置丢失。pi-web 用「距尾偏移」而不是哈希，正是为了流式期间 key 稳定。
- **防御层**：新增 `SessionSnapshotMerge.ensureUniqueIds()`（纯 JVM，幂等）。React 里重复 key 只是 console warning，`LazyColumn` 是进程级崩溃，所以这一处必须比上游更严格：快照 `synced`、对账结果、翻页页 prepend 三个写回点各兜一道；重复时**只改 id 不裁内容**（确定性 `dup<n>_` 前缀），宁可多显示一条也不静默吞掉用户可见内容。
- **单测**：新增 `HistoryItemKeysTest`（6 例）——同一条目跨窗口 key 不变、旧下标规则确实会给同一条消息两个 id（根因钉死）、同 entry 的正文/工具卡互不撞 key、跨会话不撞、entryId 缺失时锚点确定、窗口前移后对账结果无重复 key；`SessionSnapshotMergeTest` 增补 4 例覆盖 `ensureUniqueIds` 的幂等、内容不裁剪与后缀顺延。

### 🛑 压缩中不再显示第二个「红底白方块」：改成压缩图标 + 斜杠

- **现象**：压缩中屏幕上同时出现两个一模一样的红底白方块（chips 行一个、输入框右侧一个），会被读成「重复渲染」的 bug。
- **真相**：两者语义不同（`abort_compaction` vs `abort`），pi-web 也是两个按钮，但它用形态区分：压缩键 `title=stopCompaction` 是小方块图标按钮，`stopAgent` 是带文字的红色按钮。App 把两个都画成了红底白方块。
- **Fix**：压缩中的压缩键改为「压缩图标 + 一道斜杠」（红描边、红图标，背景戻回常规容器色），斜杠即「取消/不可用」的通用语义；`contentDescription` 随之变成「停止压缩」。**行为不变**：仍然可点、仍然发 `abort_compaction` —— 压缩请求没有客户端超时，卡住时的逃生口就是它，不能真的禁用。输入框右侧的停止键保持实心红圆，两者一眼可分。


## [2.0.8] - 2026-11-05

### 🔒 网关 Token 收口：只对网关自己附带，外部链接换绑必须用户确认

三处都会把「伴侣 PC 的全权凭据」交到不该给的地方。网关 Token 等价于本机命令行权限（能执行 shell、读写任意文件），所以按 SSH 私钥对待。

- **技能市场直连泄 Token**：`AuthInterceptor` 挂在主 `OkHttpClient` 上却没有 host 约束，技能搜索的兜底直连 `https://skills.sh/api/search` 会连 `Authorization: Bearer <网关Token>` 一起发出去；对方的 401 还会被当成「网关收回了密钥」，反过来弹窗逼用户重新扫码。新增 `AuthScope.shouldAttachToken()`（`scheme + host + port` 全等才附带），拦截器与媒体加载器共用同一判定；外站响应不再参与 `AuthRejection` 判定。
- **外链图片被动泄 Token**：Markdown 里的 `![](https://图床/x.png)` 会原样进入媒体加载器，且**图片进视口就自动请求**（不需要用户点击）。旧实现无条件附加 Token，等于任何一个图床都能接管伴侣电脑——尤其是一条 prompt injection 让 agent 读个带远程图片的 README 就够了。现在 `fetchBytes` 必须显式传入网关地址，host 不符一律裸取。
- **`pidroid://connect` 静默换绑**：该 scheme 是 `BROWSABLE` 的，任何网页一句 `location.href = "pidroid://connect?url=https://attacker.com&token=x"` 就能唤起 App；旧实现 ping 通即 `saveConnection`，全程不问用户一句，之后所有指令都发往攻击者服务器。现在只挂起请求，经 `ConnectLinkConfirmDialog` 展示完整 host、用户手动确认后才走与扫码/手动配置相同的 `connectToServer`（ping 成功才落盘）。App 内扫码走的是另一条路，正常配对流程不受影响。
- **顺带修掉 URL 前后空格**：`serverUrl` 读写都补 `trim()`。此前只做 `trimEnd('/')`，扫码/粘贴带进来的前导空格会让 OkHttp 抛 `Unexpected char`，用户看到的却是一句莫名其妙的「获取会话失败」。
- **判定不误伤内网图**：判定只解析 `scheme://authority`，路径里的空格、`|`、中文一律不参与——否则工作区中文路径的图片会被判成「外站」而不带 Token，变成 403 回归。同时把反斜杠按 OkHttp 规则归一化，防住 `http://evil.com\@gw/x` 这类用 `@` 伪装主机名的写法。
- **单测**：新增纯 JVM `AuthScopeTest`（13 例 / 39 断言），覆盖外站、外链图床、userinfo 与反斜杠伪装、IPv6、下划线主机名（不同端口不得归一）、默认端口省略、非 http(s)、空白与畸形输入。威胁模型与防护记录同步进 `docs/SECURITY_UPGRADES.md`。


## [2.0.7] - 2026-11-04

### 🧹 会话卡片底部元信息行改版：时钟贴最左，清掉被压扁的省略号噪点

- **现象**：部分卡片最左端冒出 `…` / `p…` 这类字母残片，右侧日期还会被卡片边缘切掉半个。
- **根因**：元信息行最左端先排 `workspaceName` + 消息条数 + 分支，右侧 token / 费用 / 时间又按固有宽度硬排。屏窄时右侧先拿走全部宽度，左侧只剩几像素 → 被省略号压成噪点；右侧超出部分则被 surface 直接裁切。
- **Fix（布局）**：最左端只保留「时钟矢量图标 + 耗时」（`Icons.Default.Schedule`，替换原来的 emoji `🕒`）；工作区名不再进入这一行（标题栏与工作区胶囊已重复），消息条数 / 分支让位给指标。
- **Fix（防裁切）**：右侧 token · 费用 · 时间合成一条 `AnnotatedString`（费用仍为成功色），左中右用 `Spacer(weight = 1f)` 撑开，空间不足时整行尾部省略而不是被切掉。
- **逻辑收口**：新增纯 JVM `SessionStatsFormatting.buildSessionMetaParts()`（顺序固定：耗时 → Token → 费用 → 时间，空值不产出片段），取代原 `buildCompactMetricsLine()`；单测覆盖顺序约定与空值过滤。

---

## [2.0.6] - 2026-11-04

### ⚖️ 收尾等待对齐 pi-web `rh()`：从「探一次」改成「600ms × 20s 直到 settle」

- **对照对象**：pi-web `useAgentSession.ts` 的 `rh()` —— 发完指令后 `await sleep(800)`，之后**每 600ms** 探一次 agent 状态，**上限 20s**；只要某一次读到「不在跑」就立刻 `loadSession` 重读正文，20s 没结论则静默放弃。
- **Fix**：App 的 `scheduleAgentCompletionSettle` 原来是“等 800ms 只探一次”，探不到状态或还在跑就直接返回 —— 命中「`agent_end` 之后服务端还差最后几笔落盘」的窗口时，正文就停在半路（表现为“明明又跑了几步，手机上没跟上”）。
- **复刻内容**（新增 `data/model/AgentSettlePolicy.kt`，纯逻辑，带 JVM 单测）：
  - 节奏：`INITIAL_DELAY_MS = 800` / `POLL_INTERVAL_MS = 600` / `TIMEOUT_MS = 20_000`，计时起点与 pi-web 一致（首等待之后）；
  - 判据：`stateKnown && !running && !isToolExecuting && !isAutoRetrying` 才收尾；**读状态失败不当成“跑完了”**；settle 优先于 timeout；超时静默放弃。

---

## [2.0.5] - 2026-11-04

### 🩹 修复会话正文截断、手动刷新不更新、退出重进才完整的缺陷

- **根因分析**：
  1. `message_end` 未定稿：流式接收长文本时若网络抖动或服务端背压丢包，正文会停在半路；上游 pi-web 在 `message_end`（携带完整权威消息体）时直接以此置换气泡，而 App 过去在此事件中未提取正文，导致半截文本被永久定格；
  2. `keepLoadedWindow` 误拦截对账：快照 revision 未变时，App 误将「不重置翻页游标」实现为「跳过整包正文对账」，导致即使服务端已将 3.7KB 完整正文写入磁盘，后台及手动刷新也因条件阻断而拒绝将完整文本写回界面；
  3. 手动刷新未强制覆盖：右上角刷新按钮未标记 `replaceHistory = true`，无法打破增量对账惰性。
- **对齐 pi-web 修复**：
  - `PiSseClient` 在 `message_end` 事件中完整提取 `msgObj.content` 中的权威正文（`message_end_sync` 带回 `content`）；
  - `MainActivity` 在 `message_end_sync` 收到权威全文时直接置换更新当前消息气泡，与 pi-web `case "message_end"` 行为 1:1 对齐；
  - `keepLoadedWindow` 仅用于保护向前翻页游标不被收缩，不再阻断 `SessionSnapshotMerge.reconcileList` 的增量修复；
  - 手动刷新按钮明确传入 `replaceHistory = true, force = true`，点击即强制以服务端磁盘最新权威记录覆盖本地视图。
- **验证**：纯 JVM 单元测试覆盖对账逻辑，配合全量流水线编译。

---

## [2.0.4] - 2026-11-04

### 🔎 Token HUD 增加「实时同步」读数（把“体感不同步”变成可读数字）

- **为什么**：此前判断「App 跟 pi-web 不同步」只能靠截图比对，没有客观读数 —— 于是每次都在猜是事件流断了、还是快照失败、还是渲染层丢项。
- **新增**：Token 用量与监控 HUD 顶部一行 `实时同步 流已连接 · 事件 2s 前 · 快照 8s 前`：
  - `流`＝SSE 连接状态（断开时文字变警示色）；
  - `事件`＝最后一条 SSE 事件距今（心跳也算，与 pi-web `AgentEventConnection` 的判定口径一致）；
  - `快照`＝最后一次**成功**会话快照距今（失败不计入，所以它能直接暴露“重接口卡住”）。
- **口径说明**：这三项正好区分三种“不同步”——事件是新的但快照很旧＝边界重读失败；两者都很旧＝流断了；两者都是“刚刚”却看不到新内容＝渲染层（回合折叠/窗口）在丢。

---

## [2.0.3] - 2026-11-04

### 🩺 闪退可定位（崩溃堆栈落盘 + 回传伴侣网关）+ 内存缓存设上限

- **背景**：「盯着一个任务跑一会就闪退」这类问题本机**没有任何崩溃记录**（logcat 需要 USB，用户手里没有），只能靠猜。这次把这条链补上：
  - 新增 `PiApp`（Application）：未捕获异常时把时间/线程/版本号/完整堆栈写到 `filesDir/last-crash.txt`，**仍然交回系统默认处理器**（不吞异常）；
  - 启动时 `MainActivity` 把它写进 logcat，并**上传给已配对的伴侣网关**（新增 `POST /api/mobile/crash`，落在 PC 侧 `server/logs/crash-*.log`），上传成功后删除文件、失败则下次启动重试。
- **内存缓存设上限**：`PiApiClient` 的内存会话快照缓存（已解析的 ChatItem，含工具输出/媒体）此前**无上限**，会随打开过的会话数线性增长，长会话里看着看着被系统回收就是这个形态。现在只保留最近 4 个会话的快照。
- **网关**：新增 `/api/mobile/crash`（沿用既有 Bearer 鉴权，只收纯文本、200 KB 上限，文件名由服务端生成，客户端不参与路径构造）；`node --check` + 既有 90 个网关用例全绿。

---

## [2.0.2] - 2026-11-04

### 🩹 修复「回合里正文之后的过程项被整段丢弃」（界面停在最后一句正文）

- **现象**：任务跑到一半，手机端界面永远停在最后一句正文（如「82 张表的技术处置已逐项完成…」）下面只剩「正在等待模型…」，而 PC 上已经又跑了十几步；底部 token 数却显示是更新的 —— 说明**数据到了、界面没渲染**。
- **根因**（`ChatScreen` 回合拆分）：旧实现用 `turnSlice.subList(0, finalAnsIdx)` 取「过程组」内容，于是**最终答复之后的过程项被整段丢掉**。而长任务的常态正是「先说一句、再继续跑工具」，因此每跑一步都被丢一次，界面看起来就冻在那句话上。
- **实测证据**（会话 `01a197fc…`）：服务端窗口 116 条、leaf `8a3f570a`；那句正文在第 98 条，其后还有 **17 条**消息；App 底部的 `↑2.5K ↓366` 恰好等于第 104 条的 usage —— 也就是说 App 手里已经有其中 6 条，只是**没渲染出来**。
- **修法（对齐 pi-web）**：抽出纯 JVM 的 `TurnPartition`，规则与 pi-web 一致 ——
  - **未答复（在跑）的回合不拆**，正文只是过程的一部分，和思考/工具卡一起待在过程组里；
  - 已答复的回合，仅当正文落在**最后一项**时才提为「最终答复」；
  - 任何情况下过程组都包含「除最终答复外的全部条目」，**不再可能丢项**；
  - 唯一的例外保留原设计：本回合有生成式 UI 卡片时把正文提出来（避免卡片被流式文字推着跳），尾部过程项仍保留。
- **验证**：新增 5 条纯 JVM 用例（在跑不拆且不丢项、widget 例外、答完才拆、答完但尾部有过程项、无正文回合）。

---

## [2.0.1] - 2026-11-04

### 🔁 会话页正文同步：源码级对齐 pi-web（去掉自造的滞后判定）

- **现象**：PC 端已跑到十几步、手机端却停在某条正文下面只剩「正在等待模型…」，而且不报错。原因是对账**把正文恢复押在周期全量快照上**（本机会话实测单次 **552 KB**，每 3–10s 一次），这个请求在隧道上卡住/超时时，界面既没有边界重读也没有可见错误。
- **对齐 pi-web 的三条机制（其余自造启发式全部删除）**：
  1. **`snapshotRevision`**（pi-web `lib/session-revision.ts` + `useAgentSession.ts:813-849`）：响应的不透明新鲜度令牌只做等值比较 —— 相同且本地条目不少于新窗口时，**只刷新树/叶/统计/模型，正文与翻页游标一律不动**；`null` 则视为不新鲜、正常重取。取代此前的 `branchLagged` 检测、`force=1` 自动重试、冷却表、`isSnapshotOlderThanLocal`/`STALE_STREAK`/只追加模式（净删约 150 行）；
  2. **单飞 + force 语义**（`loadFlightsRef`、`loadSession(..., { force: true })`）：挂载/手动刷新/分支导航带 `?force=1`，并发读同一会话共享同一份请求；
  3. **正文只在边界点读**：挂载、`agent_end`、`message_end`、`compaction_end`、工具结束、历史翻页；**删掉跑动中的周期全量对账**（`SessionSyncCadence` 整个移除），轮询只保留轻量运行态/模型档位。
- **同步失败不再静默**：此前只有「列表为空」时才提示，跑动中失败完全无声；现在沿用同一 toast 通道（10s 节流）+ 日志。
- **流判死阈值**：`45s` → `PiSseClient.EVENT_HEARTBEAT_MS × 2.5`（对齐 pi-web `AgentEventConnection` 的 `EVENT_STREAM_STALE_MS`），不再写孤立秒数。
- **不做**：不改 `tail`/`deferThinking`（πDroid 没有按需补 thinking 的通道，照抄会丢历史思考文本）、不加横幅、不加正则、不新增业务常量。

---

## [2.0.0] - 2026-11-04

### 🚀 πDroid 2.0.0 正式发布

πDroid 是基于 [agegr/pi-web](https://github.com/agegr/pi-web) 深度扩展的安卓端智能体移动掌控系统：
离开电脑时，用安卓手机完整掌控 PC 上的 Pi 任务。

#### 🌟 核心能力

- **输入框高能胶囊控制条**：一键切换模型、调节思考深度（Off / Low / Medium / High 深度推理）、一键压缩上下文，并实时展示 Token 用量与 Prompt Caching 命中率。
- **工作区与多项目管理**：顶栏下拉即可在多个工程间切换，会话按项目隔离；冷启动自动回到上次的工作区。
- **会话实时同步**：与 Web 端一致的 SSE 流式打字机输出、历史会话两阶段毫秒级 Catch-up，工具调用的执行过程逐步可见。
- **能力与扩展中心**：技能启用 / 禁用、MCP 服务与工具列表监控、`/auto-compact` 自动上下文压缩开关。
- **Worktree 与分支拓扑**：会话级分支树、worktree 创建与删除、并发与失败状态可见。
- **文件管理与预览**：全屏漫游浏览电脑文件，图片 / SVG / PDF / Markdown 双指缩放与拖拽平移，Office 文档优雅兜底到外部应用。
- **专业级保活机制**：前台服务提升进程优先级，异步任务自动持有 Wakelock / WifiLock，电池优化白名单一键引导，息屏长任务不中断。
- **应用内无感升级**：实时检测新版本、多镜像竞速下载、断点续传与 SHA-256 完整性校验，下载后直接拉起覆盖安装。
- **Termius 极客视觉**：极夜纯深色与明眸浅色双模自适应，随系统字号与窄屏自适应排版。

#### ✨ 使用提示

- 内置统一正式签名，后续版本均可直接覆盖升级，原有配置与 Token 零丢失。

---

## [1.8.91] - 2026-11-04

### 🚨 紧急修复 1.8.90 冷启动闪退（工作区分组重复 key）

- **现象**：升级到 1.8.90 后打开 App 立即闪退。
- **根因（工作区身份键与展示路径取值不同源）**：
  1. 1.8.90 为了让展示路径保留真实盘符大小写，把 `Session.workspaceKey` 改成「优先取裸 `projectKey`」，
     而 `workspacePath` 仍按 `projectRoot` 优先推导；
  2. pi-web 下发的 `projectKey` 是 `d:\myscript\pi-app`（小写 + 反斜杠），`projectRoot` 是 `D:\myscript\pi-app`，
     两者归一化后虽然同源，但**分组键与自定义目录键**从此不再使用同一套优先级；
  3. 于是同一个项目在 `groupWorkspaces` 里裂成两组，两组又都输出了同一条展示路径 `D:/myscript/pi-app`；
  4. 抽屉工作区列表用 `items(key = { it.path })` 渲染，Compose `LazyColumn` 遇到重复 key 直接抛
     `IllegalArgumentException: Key ... was already used` —— 这是**首帧即崩**，所以表现成「打开就闪退」。
- **修复**：
  - **单一取值源**：`workspaceKey` 与 `workspacePath` 全部由同一个 `rawWorkspacePath`（`projectRoot → cwd → projectKey`）推导，
    既保留展示层的真实大小写，又保证身份键唯一，不得各自采用不同字段优先级；
  - **分组层去重**：`groupWorkspaces` 按展示路径的身份键再合并一次，兼容历史上已经写坏的两份写法；
  - **UI 层兜底**：工作区列表构建完成后追加 `distinctBy { path }`，永久关闭「重复 key 导致 LazyColumn 崩溃」这一整类故障。
- **回归测试**：新增 `groupWorkspaces never emits duplicate display paths for windows projects` 与
  `workspaceKey and workspacePath stay consistent regardless of field casing` 两条纯 JVM 单测。

---

## [1.8.90] - 2026-11-04

### 🛡️ 代码质量与交互安全加固（Code Review 修复闭环）

- **Worktree 检出移除安全确认与脏工作树弹窗打通**：
  - 点击移除检出增加二次确认弹窗，防止移动端手滑误删工作树目录；
  - 打通 `dirtyWarningTarget` 状态与网关 `WORKTREE_DIRTY:` 错误反馈，当存在未提交改动时准确弹出“强制移除”确认框，修复提示用户强制清理却未弹出对话框的缺陷。
- **文件监听 SSE 长连接保活与异常守护**：
  - `PiApiClient.watchFileChanges` 改用无读取超时的专用 `sseStreamClient`（`readTimeout = 0`），消除静默 120 秒连接断开；
  - `ArtifactViewerFullScreen` 在收集文件变更流时接入 `.catch {}` 错误守护，防止流异常抛出导致 Compose UI 崩溃。
- **会话导出临时文件安全回收**：
  - `SessionExporter` 在写入公共 MediaStore 异常时自动执行 `resolver.delete` 清理挂起的记录，防止在系统公共下载目录留下 0 字节悬挂文件；清理未使用的引用。
- **分支树连线算法精细化**：
  - `BranchTreeBottomSheet` 使用 `row.parentLines` 绘制前置缩进，修复已终结分支下方挂多余竖线的渲染瑕疵，并补充对应纯 JVM 单元测试覆盖。
- **项目路径大小写保持**：
  - `Models.kt` 恢复 `rawWorkspacePath` 对真实 `projectRoot` 路径大小写的优先选用，避免在 Windows 环境下展示路径被全部小写化。

---

## [1.8.89] - 2026-11-04

### 🎨 提炼精简会话顶栏更多操作菜单文案

- **精简文案规范（去除冗余上下文与省略号）**：
  - 依照 `docs/DESIGN-labels.md` 规范与现代移动端交互习惯，将顶栏下拉菜单文案全面提炼为干净规整的纯动词短语：
    - `会话分支拓扑...` ➔ **`分支拓扑`**
    - `工作目录 Git 变更` ➔ **`Git 变更`**
    - `导出会话为 HTML` ➔ **`导出 HTML`**
    - `重命名会话...` ➔ **`重命名`**
  - 消除冗余字眼（如“会话”、“工作目录”）和不必要的省略号 `...`，菜单弹窗宽度收缩约 30%，视觉更紧凑精致，与主列表卡片菜单保持全端口径一致。

---

## [1.8.88] - 2026-11-04

### 🩹 彻底解决冷启动工作区自动落到 termius 的缺陷（对标 pi-web 机制）

- **现象**：退出 App 重新进入或离开一段时间后返回主页，界面总是顽固跳回到 `termius` 项目目录。
- **根因（多帧异步竞争 + 假失效反向洗白）**：
  1. 手机端本地 `KEY_CUSTOM_WORKSPACES` 曾通过浏览添加过 `D:\myscript\termius`，冷启动第 1 帧时服务端会话列表 `sessions` 仍在网络在途中（为空），而离线自定义工作区已同步加载，导致首帧工作区列表里只有 `["termius"]`；
  2. `LaunchedEffect` 在首帧缺乏数据就绪门禁，比对后误以为用户上次选区（如 `pi-app`）“已被物理删除”，于是错误进入兜底逻辑取出首项 `termius`；
  3. 兜底逻辑越权反写磁盘：兜底取出的 `termius` 直接调用 `onWorkspaceSelected`，在第 1 帧瞬间把 SharedPreferences 里的持久化选区强制改写成了 `termius`，致使第 2 帧完整会话到达后历史记忆已被摧毁。
- **最佳实践修复（1:1 对标 pi-web）**：
  - **网络就绪门禁（对标 pi-web `0 !== sessions.length`）**：在 `LaunchedEffect` 中加入 `if (isLoading && sessions.isEmpty()) return@LaunchedEffect` 保护，初始网络拉取期间挂起判决，绝不拿半成品列表判定工作区有效性；
  - **严禁兜底污染存储（No Dirty Stomp）**：系统的被动兜底选区只更新 Compose 内存指针，绝不调用 `onWorkspaceSelected` 改写磁盘；只有用户在抽屉/菜单中主动点击切换才允许持久化写盘；
  - **项目默认排序对标 pi-web `x(sessions)`**：`workspaces` 增加 `latestModified` 指标，项目按最近一条会话的时间戳降序排列，彻底废除“谁的陈年历史会话数最多谁排第一”的旧规则；
  - **初值读取多级容灾**：`lastOpenedWorkspacePath` 读取 `prefs.lastSelectedWorkspacePath`，若空自动回退至 `prefs.lastSelectedCwd`。
- **验证**：在 `WorkspacePathsTest` 新增单元测试，验证活跃时间（`latestModified`）优先于历史会话总数（`sessionCount`）的排序逻辑。

---

## [1.8.87] - 2026-11-04

### 🎨 顶栏人机工程学重构与会话导出路径规范化

- **会话顶栏重构（纯净对称 + 1 步直达）**：
  - 彻底移除标题行末尾突兀、不对称且低频的 `⑂ 分支` 胶囊按钮，第二行标题独占整行宽度，消除长标题截断；
  - 顶栏右侧动作区极致精简收敛为 3 个精致图标：保留高频 `[✨ AI 优化标题]`（1 步直达）与 `[🔄 刷新同步]`，新增 `[⋮ 更多操作]`；
  - 更多菜单收纳低频/重型动作：`会话分支拓扑...`、`工作目录 Git 变更`、`导出会话为 HTML`、`重命名会话...`，彻底杜绝误触。
- **会话 HTML 导出规范化**：
  - 本地伴侣网关同步升级至最新 `@agegr/pi-web@0.15.0`，彻底根除上游 0.14.2 中因正则替换导致的 `sortChildren` 500 报错；
  - 导出路径严格遵循 Android 最佳实践：流式写入系统公共存储 `Download/πDroid/pi-session-<标题>.html`，同时弹出系统原生 Sharesheet，即使取消分享文件也永久保留在手机下载目录中随时可查。

---

## [1.8.86] - 2026-11-04

### 🩹 修复 1.8.85 引入的「切到运行中的会话时阶段文案被抹掉」

- **问题**：`resetSessionScopedState()` 里包含 `agentPhaseLabel = null`，而 `openSessionWithHistory` 是**先**按 `session.isRunning` 算出「正在等待模型…」、**再**调用该清理函数，于是刚算好的阶段文案立刻被抹成 null —— 切到一个正在跑的会话时，顶部不再显示「正在等待模型…」，要等下一个 SSE/状态事件才恢复。
- **修法**：把会话级清理提到「重算运行态」之前（并在注释里写明这个顺序约束），同时删掉被它覆盖掉的散装 `streamingMessageId = null`。
- **顺带**：worktree 删除成功后刷新列表时，若用户已经关掉弹窗就只丢弃结果、不再把弹窗「复活」；`applyWorkspaceSelection` 补一句 `lastSelectedCwd` 回落语义的注释。
- **验证**：`Run Unit Tests` 全量通过（490+ 用例）。

---

## [1.8.85] - 2026-11-04

### 🛡️ 会话状态收口 / Worktree 并发与失败可见（对齐 pi-web 的单一真相）

- **活跃叶兜底（`resolveActiveLeafId`）**：旧版网关/精简快照不下发 `leafId` 时，用 `context` 窗口末条的 `entryId` 兜底 —— 与 pi-web「客户端的 leaf 就是当前上下文窗口末端」同义。以前 `leafId` 为 null 会让分支树整体不高亮（内容其实完整），用户以为分支信息丢了。滞后判定仍用**未兜底**的原始 `leafId`（拿兜底值比较永远相等）；
- **会话级状态收口（`resetSessionScopedState`）**：打开会话 / 返回列表 / 新建会话三个入口共用同一份清理（翻页游标、分支树、压缩历史、在途身份、阶段文案），顺带修掉「新建会话漏清 `olderCursor/olderLeafId/isLoadingOlder`」的跨会话泄漏——这类 bug 历史上已经补过两次；
- **工作区选区收口（`applyWorkspaceSelection`）**：worktree 切换/新建/删除回落与既有入口共用唯一落盘路径（`lastOpenedWorkspacePath` + `lastSelectedWorkspacePath` + `lastSelectedCwd`），杜绝“新加一个入口就漏写某个字段”；
- **Worktree 操作并发**：`isOperatingWorktree` 改为在 `launch` **之前**同步置真并 `try/finally` 复位 —— 快速双击不再并发发两个创建/删除请求，异常/取消也不会把弹窗卡在“处理中”；
- **删除后刷新失败**：`getWorktrees` 刷新失败时关闭弹窗，而不是继续展示一条已被删除的 worktree（再点一次就是“目录不存在”）；
- **文件监听失败留痕**：`?type=watch` 的流被 `catch` 后补一条 `PiNet` 日志（仍不崩溃），老网关不支持时不再无从排查；
- **验证**：新增 1 条纯 JVM 用例（`resolveActiveLeafId` 的四种取值口径）。

---

## [1.8.84] - 2026-11-04

### 🛡️ 代码质量与稳定性加固（Code Review 跟进）

- **文件监听异常防护**：在 `watchFileChanges` 流传递给文件预览层时加入 `.catch {}` 错误守护，防止远端 SSE 长连接中断或网络异常导致 Flow 消费端未捕获崩溃；
- **Worktree 选区持久化一致性**：在切换及创建 Worktree 时同步落盘至 `lastSelectedWorkspacePath` 与 `lastSelectedCwd`，确保冷启动行为与抽屉选区保持一致；
- **删除 Worktree 安全回落**：当移除的检出分支恰好为当前选区时，自动回退到项目主目录；转译脏工作树错误为友好中文提示。

---

## [1.8.83] - 2026-11-04

### 🩹 修复会话切换/新建会话时的分支拓扑与压缩历史状态泄露

- **会话状态隔离**：在 `openSessionWithHistory` 与 `onNewSessionClick` 流程中显式清空 `currentBranchTreeNodes`、`currentActiveLeafId` 与 `currentCompactionRecords`，彻底杜绝跨会话状态残留与顶栏误展示分支入口；
- **并发与竞态保护**：在 `onNavigateToEntry` 回调中加入当前活跃会话校验，避免切换分支期间退出页面导致的跨会话更新；
- **UI 渲染**：修复 `WorktreeManagementDialog` 中当前分支指示圆点的 Compose 语法与重载。

---

## [1.8.82] - 2026-11-04

### 🚀 全面对齐上游 pi-web 核心功能体系

- **会话成果导出（Session Export）**：
  - 接入 `GET /api/sessions/[id]/export`，支持一键生成自包含完整样式的独立离线 HTML 会话网页；
  - 导出完成后自动唤起系统原生 Sharesheet，支持直接发送至微信、QQ、坚果云或在系统浏览器中离线查阅。
- **列表卡片用量与费用（Context Usage & Cost）**：
  - 按照方案 A（对标 pi-web `vl` 组件）在 `SessionCard` 第二行以紧凑内联行展示：`时间 · 🕒 耗时 · Token数 · 费用`；
  - 接入 `POST /api/sessions/stats` 批量接口，等宽字体排布，严格遵循单行说明规范。
- **压缩历史明细（Compaction Details）**：
  - 完善 `CompactionRecord` 结构，解析 `type == "compaction"` 节点；
  - 在 `TokenStatsHudDialog` 中展示历次压缩前后 Token 变化、节省百分比与模型生成的摘要预览。
- **分支树状选择器（Branch Tree Navigator）**：
  - 方案 A 官方直角拓扑树：1:1 复刻 pi-web `vn`/`vt` 组件，通过直角折线（`├─`、`└─`）绘制多叉树；
  - 节点打上 `[U]`（用户提问）与 `[A]`（助手回答）徽标，连续单链折叠显示 `+N 轮`；
  - 活跃路径发光高亮，点击任意非当前分支节点即可触发 `navigate_tree` 瞬间穿梭切换。
- **文件预览自动刷新（File Watch via SSE）**：
  - 接入 `GET /api/files/[...path]?type=watch` SSE 流监听；
  - Agent 在后台运行 `edit`/`write` 修改代码时，手机端全屏文件查看器收到 `change` 事件加 200ms 防抖自动热刷新，无需手动点击。
- **Git Worktrees 体系与跨检出同仓库聚合**：
  - 接入 `GET/POST/DELETE /api/worktrees`，提供抽屉式管理看板，支持查看检出分支、一键切换、新建 Worktree 并开启新会话、安全移除检出（含 dirty 冲突确认）；
  - `WorkspaceGrouping` 强制以 `session.projectKey` 为第一锚点，实现同一个 Git 仓库的不同 Worktrees 在列表自动聚合在同一个项目组下，并在会话卡片上渲染 `⑂ branch` 徽标。
- **构建修复**：
  - 补齐 `WorktreeManagementDialog` 中缺失的 `androidx.compose.foundation.background` 导入；
  - 纯 JVM 单元测试覆盖树拓扑解析、多工作树跨路径聚合、统计解析、防抖防刷与导出命名。

---

## [1.8.81] - 2026-11-03

### 🔧 补齐 Worktree 管理所需的 Compose 导入与入口接线

- `WorktreeManagementDialog` 补上 `androidx.compose.foundation.background` 导入（上一版因此编译失败）；
- `MainActivity` 接入 Worktree 管理入口：`onManageWorktrees` → `loadAndShowWorktrees(cwd)`。

---

## [1.8.80] - 2026-11-03

### 🩹 补齐 ArtifactViewer 中 LaunchedEffect 导入并修复 CI 单元测试

- **文件预览**：补充 `ArtifactViewerFullScreen` 中文件监控自动刷新所需的 `LaunchedEffect` Compose 运行时导入；
- **发版闭环**：修复 `SessionExporterTest` 对导出文件名的断言（同 1.8.79 批次），确保云端流水线绿灯闭环。

---

## [1.8.79] - 2026-11-03

### 🩹 修复工作区选区记忆与测试断言对齐

- **选区记忆与兜底治理**：
  - `AppPreferences` 增加 `lastSelectedWorkspacePath`，抽屉切换、置顶专区、长按新建会话、新建远程目录统一持久化并规范化；
  - `SessionListScreen` 改用 `rememberSaveable` 并在冷启动时走 `resolveInitialWorkspaceSelection` 安全回退到真实视觉排序第一项，彻底解决冷启动回退到 `termius` 的缺陷。
- **单元测试回归修复**：
  - 修正 `SessionExporterTest` 对清洗文件名的断言，保留合法字符并对齐首尾 trim 规则；
  - 补充 `FileWatchDebounceTest` 与 `CompactionRecordParsingTest`。

---

## [1.8.78] - 2026-11-03

### 📜 聊天滚动对齐 pi-web：贴底才跟随、长落差直接跳、首屏定位不再半路停

- **现象**：重新进入会话后经常不在底部（有时很靠前），随后又"自动一屏屏刷下去"；任务在跑时往上翻历史，会被每段流式增量拽回底部。
- **根因**：App 的跟随条件是 `isAgentBusy || isImeVisible || !canScrollForward` ——
  1) 用"任务在跑"当"该跟随"，而不是"用户是否贴底"（pi-web 的原话：*Live-follow the streaming output only when the user is already near the bottom; if they scrolled up, leave them there.*）；
  2) 手指上滑**从不**改变跟随状态（全仓只有点时间轴/回到底部按钮才改那个标记），于是流式期间持续抢视口；
  3) 长落差用 `animateScrollBy(viewport)` 循环 → 一屏一屏自动翻，就是"刷屏往下滚"；
  4) 首屏 `scrollToVeryBottom()` 在列表还没测量时不做任何事，却把 `hasInitialScrolled` 置真 → 之后快照补下来的新消息再也不会重新锚到底部。
- **修法（新增 `ScrollFollowPolicy`，1:1 对齐 pi-web `lib/chat-lazy-load.ts`）**：
  - 跟随判定改为 `getLiveFollowAttached` 的等价实现：距底 ≤ 8px 算贴底；**用户往上滚立即脱离**；向下滚回 ≤ 96px 重新接上；内容在下方自然增长不影响判定（用首条可见项的位移方向，而不是"离底多远"）；
  - 落差 > 1 屏直接瞬移，≤ 1 屏才用平滑，彻底消除"自动刷屏翻页"；
  - 首屏定位只在**真的滚到尽头**（`!canScrollForward`）之后才认账，否则下一轮内容变化继续重试，不再停在半路；
  - 时间轴跳转显式脱离跟随，"回到底部"显式重新接上。
- **验证**：新增 6 个纯 JVM 用例覆盖贴底/上滚脱离/下滚重接/内容增长不误判/悬浮按钮/瞬移阈值。

---

## [1.8.77] - 2026-11-03

### 🩹 修复离开 App 一段时间后工作区选区丢失、冷启动每次都回退到 termius 的缺陷

- **现象**：每次离开 App 一段时间后再重新进入，界面不是停留在上次选中的工作区项目，而是每次都自动重置并落到 `termius` 项目。
- **根因**：
  1. 状态未持久化：工作区切换仅在 `SessionListScreen` 内部以纯内存 `remember` 存放，抽屉点击、长按菜单和新建工作区均未向外通知，也未存入 SharedPreferences 磁盘；
  2. 冷启动清零：长时间离开后 Android 系统回收 App 进程，再次启动时内存归零，触发默认兜底；
  3. 兜底策略倒挂：原兜底逻辑直接取 `workspaces.firstOrNull()`，而该列表按「活跃运行数 + 历史会话数」降序排序，在无后台运行时固定命中累积会话数最多的 `termius`。
- **最佳实践修复**：
  1. `AppPreferences` 增加 `lastSelectedWorkspacePath` 持久化，写入前自动通过 `WorkspacePaths.displayPath` 统一斜杠与尾部处理，对全局置顶专区虚拟 Key（`PINNED_WORKSPACE_KEY`）直通豁免；
  2. `SessionListScreen` 选区改用 `rememberSaveable` 保持，并暴露出 `onWorkspaceSelected` 回调；在抽屉点击工作区、点击置顶专区、长按菜单在新会话中打开、新建远程工作区等所有入口通过统一 `selectWorkspace` 分发并持久化；
  3. 决策函数 `resolveInitialWorkspaceSelection` 抽取为纯逻辑：优先选择记忆项并比对当前列表规范化形态；若记忆失效则回退至用户自定义拖拽排序与置顶规则后的 `sortedWorkspaces` 第一项，彻底对齐视觉展示，绝不再盲选 `termius`；
  4. `MainActivity` 在冷启动时自动读取持久化路径作为初值，并在打开或新建会话时同步持久化该工作区与 cwd。
- **验证**：在 `WorkspacePathsTest` 新增 4 个针对反斜杠/大小写兼容、虚拟置顶专区保留、多项目已排序兜底回退及空列表安全性的纯 JVM 单元测试覆盖。

---

## [1.8.76] - 2026-11-03

### 🩹 修复过程折叠组「只显示最新 1 条 + 显示更早的 11 条」

- **现象**：回合在跑时，折叠行明明写着「12 条消息 · 9 次工具调用 · 耗时 1m 8s」，下面却只有一张卡片，再加一行「显示更早的 11 条过程」。
- **根因**：组内窗口用的是 `remember(turn.turnNumber) { initialLimit(processTotal) }` —— 回合刚起时只有 1 条过程项，窗口就被冻在 1，之后过程项再多也不会重算（只有用户点那行才会变）。
- **修法**：记住的东西换成「用户多展开了几步」（`extraProcessSteps`），窗口每帧由**当前总数**重算：`limitFor(total, extraSteps) = min(total, INITIAL_WINDOW + extraSteps * WINDOW_STEP)`。回合刚开始 1 条就看 1 条，长到 12 条就显示 12 条，超过 120 条才出现「显示更早」，每点一次多 80 条。
- **顺带**：`ProcessWindowPolicy` 删除 `initialLimit` / `nextLimit`（被 `limitFor` 取代，避免两套窗口算法共存）；测试同步重写，并新增回归用例「回合在跑时长回合的窗口不会冻结在首次那条」。

---

## [1.8.73] - 2026-11-03

### 🩹 修正 1.8.72 引入的「滞后快照」误判（会让界面长期卡在只追加模式）

- **问题**：1.8.72 把「本地存在快照里没有的 `entryId`」当成“快照落后”。但长会话里**每跑一个回合**，窗口最老的那几条就会自然掉出快照窗口（它们仍在分支上，只是不在 `tail` 窗口内），于是：每轮对账都被判为“滞后”→ 界面长期只能往前长；空闲状态下连续 3 轮还会触发“服务端当权”，把已经拉到的旧尾巴截回窗口。
- **正解**（与“快照追得上”能同时成立的口径）：取快照里最新的 `entryId`，**它出现在本地、但不是本地的尾巴**才算快照滞后 —— 窗口向前滑动时快照尾巴根本不在本地，自然不误判；而“本地跑到快照前面”（磁盘回放 / 服务端 leaf 滞后）仍能准确识别。
- **测试**：新增「快照窗口向前滑动不算滞后」；把「工具结果单向补全」改成真实数据形状（工具卡片实际**没有** `entryId`，只能靠 `toolCallId` 配对）—— 旧写法用了一个生产环境不存在的形状。
- **顺带**：`PiApiClient` 里两个几乎重复的首/尾 `entryId` 扫描合并为 `messageEntryId(msgs, fromEnd)`；`ProcessWindowPolicyTest` 里同义反复的断言（`initialLimit(INITIAL_WINDOW) == INITIAL_WINDOW` 的等价重复）换成窗口边界断言。

---

## [1.8.72] - 2026-11-03

### 🧹 审查跟进第二轮（评审里剩下的优化项）

- **过程组窗口 200 → 120（步长 100 → 80）**：200 已经越过了这个策略存在的理由（「上百张卡片同帧组合」会卡），120 既能覆盖真实长回合、又把组合量压回有界；测试改为从常量推导展开次数，以后改窗口/步长不会再把它弄红；
- **只追加模式不再可能“粘死”**：新增 `STALE_STREAK_BEFORE_SERVER_WINS = 3` —— 连续 3 轮「快照缺本地条目」且会话已**空闲**时，允许服务端当一次权威（服务端真的丢了条目、客户端又没走分支导航时，界面不会永久显示已不存在的内容）。在跑时永远不从，不会掉断在途输出；
- **只追加模式下的工具卡片补信息**：服务端已有结果、本地卡片还在转圈（SSE 断在 `tool_execution_end` 之前）时，单向补进 `output / durationMs / isError` 并把 `isRunning` 置 false；
- **不再一次同步叠两层重试**：`readGet()` 新增 `attempts` 参数，`force=1` 那一趟只给 1 次单试 —— 之前最坏是「内层 2 次 × 外层 2 趟 = 4 个请求 × 30s」占住一个同步槽；
- **可诊断性**：会话树形状认不出来时打一条一次性 W 日志（带 `treeFormat`）—— 上次 pi-web 把 `tree` 改成嵌套数组就是静默回落，缺陷只能靠肉眼看出来；`lagRetryCooldownUntil` 加上 64 条上限；`contextLastEntryId` 改为优先取末条消息自身的 `entryId`，平行数组只作兜底；
- **对齐参考实现 / 可读性**：`roleCountsTowardTail` 不再把 `branchSummary` 算进预算（pi 的 `countsTowardTail` 只算 user/assistant/compaction）；`flattenTree` 里的非局部返回改成独立的 `flattenRoots()`；补充「节点缺 entry 对象时跳过自身但仍走子节点」的用例，测试里的双 `!!` 改用 `requireNotNull`。
- **验证**：新增 4 个纯 JVM 用例（工具结果单向补全、只追加模式的空闲出口、分支摘要不占预算、形状漂移兜底）。

---

## [1.8.71] - 2026-11-03

### 🧹 1.8.70 的代码审查修正（执行过程同步链）

- **修正「快照落后」的判定口径（重要）**：1.8.70 把「响应 `leafId` ≠ `context` 末条 `entryId`」也当成了“快照落后”而进入只追加模式。当分支最后一条恰好是不产出消息的 `model_change` / `thinking_level_change` 时，这个条件会**恒成立**，界面就永久停在只追加：服务端后续的正文修正再也同步不回来。现在只追加的判定只问一个结构问题：**本地是否存在快照里没有的 `entryId`**（在途 SSE 条目不计数），`branchLagged` 只用于决定要不要补一次 `force=1`；
- **`force=1` 不再无限重试**：命中后补一次仍修不好（真滞后且服务端修不回来、或上述天然不相等）时，该会话冷却 5 分钟不再双请求，也把 W 日志压到一次，避免在跑的会话每 3~10s 双请求 + 一条日志；
- **修正顶部哨兵行号错位**：`HistoryTopLoader` 的渲染条件（`hasMore` | 加载中 | `turnsBefore > 0`）与用于时间轴/视口错定的 `showTopSentinel` 各写了一套，在某些组合下会错开一行，导致时间轴跳转/翻页锜点偏一行。现在由同一个 `showHistoryTopLoader` 两层派生；
- **日志节流**：`PiSync: stale snapshot …` 从每轮对账一条改为同一小时内最多一条；
- **文档**：修正 CHANGELOG 1.8.70 里的错字（点目 → 条目）；新增用例「本地只有未落盘在途项时不进入追加模式」固定上述判定口径。

---

## [1.8.70] - 2026-11-03

### 🔍 修复「PC 上几十上百次工具调用，手机上只同步到中途某一步」（执行过程不同步）

- **现象**：pi-web 里一轮任务已经跑到 git tag / 等 CI 那一步（几十张工具卡），手机端聊天流却永久停在几分钟前的「90 个测试全绿…」，下面只剩一行「正在执行 bash...」；底部状态栏的 token 统计却是新的 —— 说明**状态在同步、正文没有**。
- **根因（四条，全部实现在数据层）**：
  1. **响应里的 `tree` 是嵌套数组**（pi-web 0.13 起为 `[{entry, children}]`，带 `treeFormat` 标记），旧解析只认 `entries` / `tree.entries` / `messages` 三种平铺形状，嵌套树直接落空 —— `entries` 分支形同死码，App 永远只能拿到 `context.messages` 那一小段上下文窗口（本例实测：会话 1097 条消息 / 548 次工具调用，窗口只给 117 条）。现统一拍平三种形状（摘要树不带 `message` 正文时自动回落，绝不伪造内容）；
  2. **服务端快照会「半边新半边旧」**：`stats`（覆盖全部 entry）是新的，`context`（按 leaf 往回走）是旧的 —— 成因是 pi-web 的 live wrapper 在外部进程写同一个 jsonl 时内存 leaf 滞后于磁盘，它自己的前端靠 `?force=1`（`evictIfDiskAhead()`）规避，App 从来没带过。现在解析时用「响应 `leafId` vs `context` 末条 `entryId`」检测滞后，命中就补一次 `force=1`；
  3. **对账会把本地更新的尾部截掉**：以前无条件以快照为权威，本地「已落盘但快照里没有」的**条目**被当成过期分支删除，再加上 `while (messages.size > reconciled.size) removeAt(lastIndex)` 直接截尾 —— 于是正文只能往回退。现在检测到快照落后时改为**只往前长、不回滚**（保留本地已落盘条目、已匹配条目保留更完整的正文，翻页游标也不被旧快照覆盖）；
  4. **过程组窗口太窄 + 续批判定单一**：过程折叠组原来只渲染最近 40 条（pi-web 的 `ProcessDetailsGroup` 展开是全量的），现在提到 200 条（仍保留「显示更早的 N 条过程」兜底）；上滑自动续批同时看 `hasMore` 与「未加载回合数」，任一存在就继续翻，不再因为某一次快照把 `hasMore` 报成 false 就永久停手。
- **顺带修正**：条目树切窗改为按 pi 的 `tail` 语义（只数 user/assistant/压缩里程碑，不数 toolResult），并保证窗口不比 context 窗口更短；翻页游标改为跟着**实际渲染窗口的第一条**走，避免丢页/重复。
- **验证**：新增 18 个纯 JVM 用例（`SessionBranchWindowTest` 共 11 条：嵌套树拍平、可见消息预算、leaf 滞后判定；`SessionSnapshotLagTest` 共 7 条：滞后快照不截尾、不回滚正文、乐观提问仍保留、在途项不触发追加模式、快照追上后恢复以服务端为准），并更新 `ProcessWindowPolicyTest` 期望值。

---

## [1.8.69] - 2026-11-03

### 🚀 适配 pi-web 0.14 / pi 1.5.0：侧聊与子代理不再混入会话列表

- **会话列表不再混入侧聊与子代理**：pi-web 0.14 用 `SessionInfo.relation.kind`（`side` | `subagent` | `fork`）标记继生会话，它自己的侧栏靠 `isHiddenRelation()` 把前两者藏起来。App 之前完全不解析该字段，于是 PC 上开一个侧聊，手机上就会多出一条看起来像普通会话的条目。现在新增 `SessionRelation`，解析 `GET /api/sessions` 时按 `kind` 过滤；`fork` 仍是顶层会话，**旧版网关不下发 `relation` 时判定为可见**（向后兼容）；
- **伴侣网关依赖升到 pi 1.5.0**（`@earendil-works/pi-coding-agent` / `pi-ai` 1.4.0 → 1.5.0）：pi 1.5 唯一的破坏性变更是「工具参数校验改用 `@earendil-works/pi-schema`」，而网关的提示词增强走 `ModelRuntime.completeSimple`、**不传 tools**，因此不受影响。已实测 `ModelRuntime.create()` 正常、目录可枚举 1602 个模型、`models.json` 里 codywon / routergo / deepseek 三家的自定义模型全部能解析；
- **验证**：网关 90 个自动化用例全绿；App 侧新增 8 个纯 JVM 用例覆盖 `relation` 可见性（side/subagent 隐藏、fork 可见、无 relation 可见、未知 kind 不隐藏、大小写不敏感、其余字段解析不受影响）。

---

## [1.8.68] - 2026-11-03

### 🩹 修复「带截图的提问在模型答完后又在底部重复出现」

- **现象**：发送带截图的指令，模型正常执行并把答案输出完了，列表底部却又冒出一条一模一样的提问（还在答完的答复**下面**），直到本轮结束才自己消失，偶尔还伴随「有 1 条重复的未送达提问已撤回」之类的提示。
- **根因**：pi 内核会把附加图片缩放后**往落盘的提问正文末尾追加一行给模型看的尺寸提示**（`[Image: original 1220x2656, displayed at 919x2000. Multiply coordinates by 1.33 to map to original image.]`）。手机截图 1220×2656 必然被缩放，所以每次发截图都命中：服务端那条正文 =「用户原话 + 提示行」，本机乐观气泡 =「用户原话」，快照对账（`SessionSnapshotMerge`）按正文认领时**永远对不上**，于是乐观气泡被当成「尚未落盘的在途提问」追加到列表末尾 —— 顺序就变成「提问 → 答复 → 提问」，看上去像自己又发了一遍。同一个错配还让「未送达结算」（`PendingPromptSettlement`）把**已经送达**的提问判成没送达，于是回收气泡、把正文塞回输入框。
- **修复**：新增纯 JVM 的 `UserPromptText`（剥掉 `[Image: original …]` / `[Image converted from …]` / `[Image omitted: …]` 三类提示行 + 引导前缀，再比较），快照对账认领乐观提问、未送达判定、发送回执核对一律走它；历史快照解析时也剥掉提示行，用户气泡与「从此处编辑」回填不再显示这行机器话术。单测覆盖多种提示行组合、正文含方括号的误伤边界，以及「带截图的提问必须被服务端那条认领、顺序保持提问→答复」的端到端回归。

## [1.8.67] - 2026-11-03

### 🔍 长公式不再被压成小字 + 新增「聊天正文字号」

- **修复块级公式字号被缩小**：KaTeX 排出的公式是一个不可断行的整体，超出卡片宽度时此前会整体等比缩小 —— 一条 `L = 水平 61.87m + … = 87.18m < 90m` 会被压到 30% 左右，字高只剩 4~5px。现在先在公式的**顶层运算符**（`+` / `=` / `,` / 两侧带空格的 `-`）处断行，用 `gathered` 逐行排版，字号保持原大；已有 `\begin` 多行结构、`\left/\right` 成对括号、花括号不配平或没有可断点的公式一律**原样不动**（宁可不动，也不能切出一条坏公式）。
- **新增「系统设置 → 外观与反馈 → 聊天正文字号」**：`−` / `+` 步进，80%~160% 共 9 档（10% 一档），默认 100% 与历史观感完全一致。只缩放会话正文（Markdown 正文 / 代码块 / 标题 / 公式），不放大标题栏与输入框，避免挤掉输入区；公式字号同步跟随档位（WebView 内部把 sp 显式换算成 dp，不吃 Compose 的 fontScale）。
- 档位值会吸附到 10% 网格并夹在合法区间内，SharedPreferences 里被写坏的旧值（越界 / NaN）自动回正；单元测试覆盖档位计算与公式断行的全部边界（断点安全、内容不丢、行数随宽度/字号单调）。

## [1.8.64] - 2026-11-02

### 🎨 思考深度选择去繁就简：梯级色彩预警与零冗余排版

- **三级色彩梯次告警**：将此前 `high`、`xhigh`、`max` 统一混用单一琥珀黄的处理，重构为随消耗强度逐级递进的色彩体系：
  - `high`（深度）：深金 / 琥珀（浅 `#92400E` 7.09:1 / 深 `#FBBF24` 10.36:1）
  - `xhigh`（超深）：炽暖橙（浅 `#C2410C` 5.18:1 / 深 `#FB923C` 7.64:1）
  - `max`（极限）：烈焰珊瑚红（浅 `#BE123C` 6.29:1 / 深 `#FB7185` 6.43:1）
- **彻底去除界面废话**：删除右侧重复的「深度推理」胶囊徽章，不造「重载推理 / 极限算力」等空洞术语，也不显示内部 `xx k` Token 单位；整行只保留 `value` 与纯净中文名，仅凭颜色传达计算强度。
- **聊天顶栏同步**：顶栏思考级别状态图标与文字实时跟随当前档位的梯次色彩，一眼可知当前开销等级。
- **单测健全**：`ColorContrastTest` 增至 18 组，确保三档新 Token 在 5 套主题全表面严格满足 WCAG AA（≥ 4.5:1）。

## [1.8.63] - 2026-11-02

### 🩹 修复任务列表状态筛选胶囊在窄屏 / 大字号下折行变形

- **现象**：列表顶部「全部 / 未读 / 运行中 / 已完成」四个筛选胶囊在窄屏或大字号下放不进一行，`Row` 会把最后一个胶囊压到剩余宽度里，导致标签在胶囊内部折成两行（「已完成」换行「(71)」），单个胶囊被撑高、整行高度不齐，右侧视觉重心被顶歪。
- **根因**：四个胶囊的内容宽度合计超过屏幕可用宽度（`全部 (71)` + `未读 (1)` + `运行中 (0)` + `已完成 (71)`），而标签用默认的自动换行排版，`Row` 按剩余宽度约束最后一个子项，折行就发生在胶囊内部而不是胶囊之间。
- **修复（`SessionListScreen.kt`）**：
  1. 筛选行改为单行 + `horizontalScroll`：滚动测量给子项无界宽度，胶囊永远按内容完整单行展开，宽度不足时整行左右滑动，不再压缩任何一个胶囊；
  2. `FilterPill` 的标签显式 `maxLines = 1` / `softWrap = false` / `overflow = Clip`，从排版层面保证胶囊内永远不折行（大字号、三位数计数、新增长标签都不会再撑高整行）。
- **影响面**：仅任务列表页筛选行的排版，筛选逻辑、计数语义与点击行为均未改动。

## [1.8.62] - 2026-11-02

### 🛡️ 代码质量与交互稳定性加固：WebView 避免冗余重载、手势生命周期防抖与边界平移约束

1. **SVG WebView 重组防重载保护 (`MediaPreviewComponents.kt`)**：
   - 修复在 `AndroidView.update` 中无条件执行 `loadDataWithBaseURL` 导致父级重组（如点击屏幕切换沉浸模式）时重置用户当前缩放比例与平移位置的缺陷，通过 `tag` 比对仅在内容变化时重载；
   - 增加 `onRelease` 显式销毁 WebView 与停止后台加载，杜绝内存泄漏；
   - 强化 SVG 脚本净化，彻底移除自闭合脚本标签与 `on*` 内联事件属性。
2. **位图图片手势稳定性优化 (`MediaPreviewComponents.kt`)**：
   - 移除 `pointerInput` 中的 `scale` 变动键，避免在双指缩放过程中因状态改变导致手势协程被反复取消重启；
   - 增加平移物理边界限制（`maxOffsetX`/`maxOffsetY`），防止将图片拖拽出可视区域。

## [1.8.61] - 2026-11-02

### 📐 彻底重构 SVG 缩放引擎：采用 `viewBox` 原生动态投影，实现真矢量 100% 锐利缩放

- **根因分析**：
  此前 `SvgWebViewCanvas` 是通过 CSS 的 `transform: scale(...)` 对包含 SVG 的 HTML 容器 `div` 进行缩放。在 Chromium / Blink 渲染内核中，应用了 CSS `transform: scale` 的 DOM 节点会被光栅化缓存在 1x 分辨率的 GPU 纹理图层中，缩放时仅仅是由 GPU 拉伸该位图图层（双线性插值），导致放大后线条和文字变得模糊、出现锯齿，未能发挥矢量图形的本质优势。
- **改动（真矢量渲染）**：
  1. **直驱 `viewBox` 投影矩阵**：彻底废除 CSS 位图缩放，改由 JavaScript 直接计算并动态更新 SVG 根节点的 `viewBox`（`curX curY curW curH`）；
  2. **原生实时光栅化**：每次捏合放大或拖拽平移，浏览器矢量绘图管线直接以屏幕物理子像素精度重新计算并光栅化贝塞尔曲线、线条、字体与渐变，在 2x、5x、15x 甚至任意倍率下均保持 **100% 纯正矢量锐利高清，零模糊、零像素化**；
  3. **手势平滑顺畅**：单指平移、双指以触控中心为焦点的捏合缩放、双击放大/复位以及右下角复位胶囊无缝协同工作。

## [1.8.60] - 2026-11-02

### 🔍 增强 SVG 与图片全手势缩放查看（双指捏合缩放 + 拖拽平移 + 双击复位）

1. **SVG 矢量无损手势缩放 (`SvgWebViewCanvas.kt`)**：
   - 支持**双指捏合平滑缩放（0.5x ~ 10x）**与拖拽平移，矢量路径与文字在任何放大倍数下均保持 100% 锐利清晰、无任何栅格化模糊；
   - 支持**双击快速在 1x 与 2.5x 之间放大与复位**；
   - 右下角新增优雅的**悬浮缩放比例指示胶囊**（如 `100%`、`250%`），点击胶囊即可一键秒级复位至 100%；
   - 安全加固：自动剥除 SVG 中可能携带的 `<script>` 脚本，且限制 WebView 禁止任何网络/文件逃逸。
2. **位图图片（PNG / JPG / WEBP / BMP）手势缩放 (`ZoomableImageCanvas`)**：
   - 同样支持双指捏合自由缩放（0.75x ~ 6x）、拖动画布与双击复位，看 4K 大图与长海报细节更加自如。
3. **浏览电脑目录位置记忆确认**：
   - 每次切换目录均会自动持久化记住上次所在目录，下次打开「浏览电脑文件...」直接无感从该位置继续漫游；
   - 同时支持通过首行 `.. (返回上一级)` 或返回按键一路回退直至盘符根目录（如 `D:/`、`C:/`）。

## [1.8.59] - 2026-11-02

### 🛡️ 代码质量与可靠性优化：同路径规范化防重置、下载走 HTTP/1.1 防断流与非阻塞重试

1. **同目录比对使用 `WorkspacePaths.samePath` 防止状态意外重置 (`MainActivity.kt`)**：
   - 修复在 Windows 宿主机上 `D:/` 与 `D:\` 因斜杠字符不同被普通 `equals` 判为不同目录，导致在进入子目录后被在途校验重新覆盖重置为根目录的问题；
2. **普通目录导航消除冗余网络校验 (`MainActivity.kt`)**：
   - 普通子目录导航优先直接获取目录列表（1 RTT），仅当遭遇 403 权限拒访时才触发加白重试（2 RTT），降低多层级浏览延迟；
3. **文件下载接入 HTTP/1.1 连接池 (`FileDownloader.kt`)**：
   - 将系统「下载」与 SAF 保存文件流程切换至大文件专用的 `fileOkHttpClient`（HTTP/1.1），杜绝公网 Nginx 代理下的 `stream was reset: INTERNAL_ERROR` 异常断流；
4. **大文件重试延迟改为非阻塞 `delay` (`PiApiClient.kt`)**：
   - 将重试等待从阻塞线程的 `Thread.sleep` 改为协程原生的非阻塞 `delay`，避免线程饥饿；
5. **Office XML 解析防御加固 (`OfficeXmlPreview.kt`)**：
   - 限制 Zip 解包最大条目数量（`MAX_ZIP_ENTRIES = 1000`），并在列序号解析中增加大数值截断保护，防御极端异常文件；
   - 优化全屏查看器顶栏操作，避免在 Office 视图中显示文本换行按钮。

## [1.8.58] - 2026-11-02

### 🖼️ 修复 SVG 打不开（「读取文件失败: HTTP 200」）+ 浏览电脑文件记住上次位置并可一直向上退到盘符根

#### 1. SVG 能按代码路径打开了

- **根因**：pi-web 的 `GET /api/files/<path>?type=read` 只对**文本类**回 `{content,nextOffset,...}`；
  一旦 `getImageMime()` 命中（**包括 SVG**）就直接把文件原样流回来，`Content-Type: image/svg+xml`。
  客户端旧实现一律把响应当 JSON 解，于是 SVG 必然报「读取文件失败: HTTP 200」——
  状态码是 200 却读不出内容，正是这个错位的直接证据。
- **改法**：新增 `FileListParsing.isJsonBody(contentType, body)`，响应成功但不是 JSON 时直接当原始文本用
  （`truncated=false`、`nextOffset=size`），并附带 NUL 嗅探（`looksBinary`）避免把二进制当文本渲染。
  现在 `.svg` 会走已有的 `SvgWebViewCanvas` 矢量渲染（可缩放、可切源码视图）。

#### 2. 浏览电脑文件：记住上次目录，并且可以一直退到盘符根

- **会记住**：每次切目录都会写入 `prefs.lastBrowsedDirectory`，下次点「浏览电脑文件...」直接从那里继续；
  路径失效时自动回落到家目录 / 首个可用盘符。
- **之前记得太死**：`FilesScreen` 把「起始目录」也当成了导航边界，于是在 `D:/a/b` 重新进入后
  「..（返回上一级）」是隐形的、按返回直接退出页面。本次给该模式加 `allowRoam`：
  「浏览电脑文件」入口下向上退只受 `parentPath()` 约束（到 `D:/` 、`/` 或 UNC 共享根才退出），
  而工作区长按菜单的「浏览文件」仍然锁在工作区内，不改变原有边界语义。

## [1.8.57] - 2026-11-02

> `v1.8.55`（`Modifier.align(...)` 在非 BoxScope 作用域编译失败）与 `v1.8.56`（单测里
> `assertEquals(Int, Int, String)` 无匹配重载导致测试源集编译失败）两个 tag 都未产出安装包，
> 本版是修复后的同内容首发。

### 🎯 返回箭头点不动、PNG 打不开、Office 只能跳外部应用 —— 三件事一起修

#### 1. 左上角返回箭头失灵：不再用「透明热区盖在页面上」实现边缘右滑

- **根因**：`EdgeSwipeBackArea` 是一个 `Box(width = 28.dp, fillMaxHeight)` 的叠加层。Compose 的命中测试
  只把指针事件交给**最上层命中的兄弟节点**，所以这条 28dp 宽的热区会把所有落在它上面的点击全部吞掉。
  之前试图靠「把热区挪到顶栏下方」绕开，但只要尺寸/内边距算错一点，返回按钮就再次失效 ——
  这就是「点一下没反应，左滑却正常」的直接原因。
- **改法**：新增 `Modifier.edgeSwipeBack(...)`，把边缘手势挂在**根容器**上：事件从父节点流过，
  子节点（包括顶栏 `IconButton`）照常收到点击；只有「起点在左边缘 28dp 内 + 横向位移超过 48dp」
  的手势才会被拦截并触发返回，纯点击不消费任何事件。旧的叠加层组件因此整体移除。

#### 2. PNG（尤其 4K 大图）打不开：`stream was reset: INTERNAL_ERROR`

- **根因一**：公网链路是 `App → nginx(h2) → frp → 网关 → pi-web`。实测 nginx 在向前端推送数 MB 二进制
  流时会偶发 `RST_STREAM(INTERNAL_ERROR)`，同一请求改成 HTTP/1.1 则稳定返回完整正文。
- **根因二**：读完才解码。6.1MB 的 4K PNG 直接 `decodeByteArray` 会产出 4096×4096 ARGB_8888（约 67MB）
  位图，手机上大概率直接 OOM，表现出来也是「图片打不开」。
- **改法**：新增大文件专用 OkHttp 客户端（强制 HTTP/1.1 + 90s 读写超时），图片/PDF/Office 原始字节
  均走它，并在失败时**整份重试一次**；图片解码改为先读尺寸、按 2 的幂次降采样到≤2048px 再解码。
  失败时的兜底 UI 也从误标的「办公 / 外部文档」改成「图片预览失败 + 重试 / 用其他应用打开」。

#### 3. Office 预览：xlsx / pptx 端内真预览（对标并补全 pi-web 的缺口）

- pi-web 只有 `.docx` 走服务端 mammoth 转 HTML，`.xlsx` / `.pptx` 在 pi-web 侧同样只能「外部打开」。
- 新增纯 JVM 的 `OfficeXmlPreview`：xlsx / pptx 本质是 zip + XML，直接用 `java.util.zip` 解包并
  扫描 OOXML 文本即可，**不需要服务端新增任何接口**（网关是纯反代，也不允许自造接口）：
  - `.xlsx` → 读出工作表名、共享字符串 / 内联字符串 / 数值，渲染成可横向滚动的等宽表格
    （首行表头样式，最多 200 行 × 24 列，超出提示截断）；
  - `.pptx` → 按页码顺序抽取每页文本（标题 + 要点），渲染成幻灯片卡片；
  - `.docx` 仍走服务端 mammoth HTML 预览；旧版二进制 `.doc/.xls/.ppt` 与压缩包保留
    「用 WPS / 系统 Office 打开 + 下载到手机」卡片。

## [1.8.55] - 2026-11-02

### 🎯 修复文件管理器与预览器左上角返回箭头点击无响应的问题

- **根因分析**：
  在 `FilesScreen` 与 `ArtifactViewerFullScreen` 中，此前全屏边缘滑动返回组件 `EdgeSwipeBackArea` 被置于根布局 `Box` 最外层（覆盖在 `Scaffold` 之上），其高度为 `fillMaxHeight()`。由于 Compose 的手势拦截机制，位于 (0,0) 至 (32dp, 屏幕底端) 整个竖直长条的手势检测层直接截获并吸收了所有指针点击事件，导致位于左上角的导航图标按钮 `IconButton` 无法接收到触摸点击（表现为“点击左上角箭头没反应，而右滑边缘却能正常返回”）。
- **改动**：
  1. **层级下沉与隔离**：将 `EdgeSwipeBackArea` 移入 `Scaffold` 的内容区域（`padding(innerPadding)`），完全避开顶栏 `TopAppBar` 区域，使得左上角返回箭头（`IconButton`）能够 100% 顺畅接收点击；
  2. **顶栏按钮热区优化**：显式设定左上角返回按钮热区为标准 `48.dp`，提升手指轻触容错率；
  3. **查看器同步修复**：在全屏代码/文档查看器（`ArtifactViewerFullScreen`）中做同步层级隔离，彻底消除顶栏与边缘手势的触摸冲突。

## [1.8.54] - 2026-11-02

### 🐛 修复磁盘根目录（如 D:\、C:\）因末尾斜杠编码导致 Nginx/网关报 HTTP 404 的问题

- **根因分析**：
  在 `PiApiClient.encodeFilePath` 中，旧逻辑直接以 `split('/')` 切割路径，当传入磁盘根目录（如 `D:\` 或 `D:/`）时，末尾的斜杠切割出了尾随空串，拼接后产生了带有末尾斜杠的 API 路由段 `D%3A/`（即请求 URL 为 `/api/files/D%3A/?type=list`）。在公网反向代理（Nginx / frp）转发下，以末尾斜杠结尾的请求会脱离代理 location 规则直接被上游路由阻断并抛出 `HTTP 404 {"detail":"Not Found"}`；而在不带末尾斜杠的 `D%3A` 下则完全正常响应 200。
- **改动**：
  1. **对齐上游 `encodeFilePathForApi` 算法**：抽离至 `FileListParsing.encodeFilePath` 纯 JVM 方法，统一切段并自动 `filter { it.isNotEmpty() }`，消除任何尾随斜杠（`D:\` 准确编码为 `D%3A`，`D:\path\` 编码为 `D%3A/path`），彻底根治 404 问题；
  2. **URL 前缀保护**：`fileApiUrl` 与 `uploadFiles` 对 `baseUrl` 增加 `.trimEnd('/')` 保护，防止多余双斜杠造成路由异常；
  3. **丰富错误解析**：`FileListParsing.parseError` 新增对服务端 `detail` 与 `message` 字段的提取，提升异常可读性；
  4. **单测覆盖**：在 `FileListParsingTest` 中新增 `encodeFilePath accurately encodes paths and strips trailing slashes` 单元测试，全面覆盖 Windows 盘符根目录、子目录与 UNC 路径。

## [1.8.53] - 2026-11-02

### ⚡ 修复「浏览电脑文件」点击后抽屉收起但页面未出现的延迟与阻断问题

- **根因分析**：
  此前点击「浏览电脑文件...」时，逻辑为先挂起等待抽屉收起动画（~300ms），随后在异步网络协程中调用 `validateRemoteDirectory(path)`；当默认路径（曾硬编码兜底为 `D:/`）在没有 D 盘的宿主机（如单固态笔记本、Mac、Linux）上校验失败（400），或在弱网/隧道下存在延迟时，`browsingWorkspaceCwd` 在网络返回前始终保持 `null` 且失败时直接中断，导致用户直观感知为「侧边栏自动退出了，但文件浏览页面没有出现」。
- **改动**：
  1. **同步直达零延迟**：点击「浏览电脑文件...」同步且无阻塞地设初始路径（优先已有会话目录/工作区，兜底家目录 `~`），立即切换并激活全屏 `FilesScreen`，抽屉异步并发关闭；
  2. **白名单校验与目录列表平滑融合**：在 `onLoadDirectory` 阶段自动对目标目录执行 `validateRemoteDirectory` 加入可读白名单并取回规范化物理路径，任何盘符（`C:`、`D:`、家目录）切换均受此保护；
  3. **智能容灾与磁盘切换**：磁盘盘符菜单置顶包含「用户主目录 (~)」及全部枚举盘符，即便初始路径有误亦可停留在页面内直接切换或重试。

## [1.8.52] - 2026-11-02

### 🐛 紧急修复：进入全屏文件管理器时因 SnapshotStateList 未适配 Bundle 导致的直接闪退

- **根因分析**：
  在 `FilesScreen` 中，多级导航历史栈使用了 `val navigationStack = rememberSaveable { mutableStateListOf<String>() }`。Jetpack Compose 的默认 `rememberSaveable` 依赖 Android 的 `SaveableStateRegistry`，要求保存的对象必须能放入 Android `Bundle`（实现 Parcelable/Serializable 或基本数据类型）。而 Compose 的响应式快照列表 `SnapshotStateList` 并非 Bundle 兼容类型，进入 `FilesScreen` 首帧组合时会无条件触发 `IllegalArgumentException: SnapshotStateList cannot be saved using the current SaveableStateRegistry`，导致整个应用崩溃闪退。
- **改动**：
  将 `navigationStack` 改为标准的 `remember { mutableStateListOf<String>() }`，避免向 `SaveableStateRegistry` 注册非法类型；当前目录 `currentPath` 依旧由 `rememberSaveable` 保护，彻底杜绝闪退，保证多级目录漫游与返回平稳流畅。

## [1.8.51] - 2026-11-02

### 🧪 补齐文件预览异常链路单测与构建脚本容错增强

1. **预览分派与异常处理单测加固 (`LoadFilePreviewTest`)**：
   - 图片字节断言由长度匹配升级为 `assertArrayEquals` 全量内容精确匹配；
   - 补齐 `RemoteFilePreviewLoader` 全部 4 个异常降级分支的纯 JVM 单元测试覆盖（图片拉取失败、PDF 下载缓存失败、文本分块读取失败、DOCX HTML 转换异常降级）；
   - PDF 临时测试文件添加 `deleteOnExit()`，防止测试进程非正常退出时文件残留。
2. **构建脚本容错性增强 (`app/build.gradle.kts`)**：
   - `-Ppidroid.minify` 参数解析增加不区分大小写与空白字符修剪（`trim().lowercase()`），包容命令行输入 `True` / `TRUE`，同时坚决阻断 `flase` / `0` 等非法拼写。

## [1.8.50] - 2026-11-02

> `v1.8.49` 的 tag 因测试用例对归档类文件的特定描述断言与分词分类不一致未产出安装包，本版为修复后的同内容首发。

### 📱 优化文件管理与预览体验：全屏独立漫游、手势返回、PDF 缩放翻页与 Office 优雅兜底

1. **视图层级与导航重构（从弹窗到独立全屏）**：
   - 彻底废除抽屉中转弹窗 `BrowseComputerDialog`，点击「浏览电脑文件...」直接无感进入全屏文件管理器（`FilesScreen`）；
   - 采用移动端文件管理器最佳实践的层级式导航：点击子文件夹前进进入该目录，首行提供 `.. (返回上一级)`；
   - 顶栏左上角返回按钮与系统返回键：在子目录时平滑返回上一级，只有在根目录时才退出文件管理器；
   - 全屏增加屏幕左边缘（32dp 热区）**向右滑动手势返回**（`EdgeSwipeBackArea`），单手操作极其顺畅。
2. **PDF 文档预览手势升级**：
   - 支持**左右滑动手势切页**（单指左右拖动快速翻页）；
   - 支持**双指捏合缩放（Pinch-to-zoom 0.8x ~ 5x）**与缩放后的平移拖拽，双击可快速在 1x 与 2.5x 之间重置/放大；
   - 底部保留优雅的悬浮页码指示栏。
3. **Office 文档与复杂格式优雅兜底**：
   - 自动识别 Word (`.doc/.wps`)、Excel (`.xls/.xlsx/.et`)、PPT (`.ppt/.pptx/.dps`) 及压缩包 (`.zip/.rar/.7z` 等)；
   - 居中呈现精美文档卡片（`ExternalDocumentCard`），显示格式大图标、文件名、大小及详细说明；
   - 提供「🚀 用其他应用打开（WPS / 系统 Office）」主按钮，一键下载并调用系统 `Intent.ACTION_VIEW` 秒级拉起外部阅读器；同时保留「⬇️ 下载保存到手机」次按钮。
4. **文件预览全屏边缘右滑退出手势**：
   - 在查看代码、PDF、图片或 Office 文档卡片时，屏幕左侧边缘右滑直接顺滑退出预览回到文件列表。

## [1.8.48] - 2026-11-02

### 🧭 压缩链路改为 1:1 对齐 pi-web：不设客户端超时，压缩中可「停止压缩」

上一版（1.8.47）虽然消除了「压缩假失败」，但仍有一处**有意偏离**：给压缩配了 10 分钟超时。
按「原则上必须对齐 pi-web」重新核对了 pi-web 0.13.0 的**前端包与服务端产物**，实际行为如下：

| 维度 | pi-web 的真实行为 |
| --- | --- |
| 客户端超时 | **完全没有**。agent 命令客户端是一个裸 `fetch()`：无 AbortController、无 signal、无 timeout（`async function aI(id,payload){await fetch(...)}`） |
| 压缩中状态 | 会话 store 的 `run.compacting`（服务端 `get_state.isCompacting`）+ 一次请求标记，**按会话**；状态行文案 `chat.compacting` / `chat.compactingQueued` |
| 重入保护 | `handleCompact` 开头 `if (!sessionId \|\| isCompacting) return true` —— 已在压缩就直接忽略 |
| 卡住/太久怎么办 | 同一按钮就地变成红色「停止压缩」（`chat.stopCompaction`）→ `{"type":"abort_compaction"}` |
| 异常展示 | 输入区上方的红色 inline alert（`role="alert"`）+ ✕ 关闭（`chat.dismissCompactError`），不走 toast |
| 输入框 | 压缩中 placeholder =「正在压缩…消息会排队，压缩结束后发送」 |

据此对齐：

- **去掉压缩的所有客户端超时**（`readTimeout(0)` = 不超时）—— pi-web 等多久我们就等多久；
  网络真正断开时仍有 OkHttp HTTP/2 ping（20s）与连接错误兜底。
- **新增「停止压缩」：压缩中同一个圆圈按钮变红，点一下发 `{"type":"abort_compaction"}`**（pi 会
  同时 abort 手动/自动压缩控制器）；卡住的压缩不再只能干等，也不再弹假失败。
- **取消不当失败**：新增 `CompactionFailure.isCancellation()`，用户自己停的压缩（pi 会把
  `compact()` 以 `Compaction cancelled` 拒绝）提示「已取消压缩」；真失败（`Compaction failed` /
  `Already compacted` / `Nothing to compact`）仍原样报错。
- 输入框 placeholder 对齐：压缩中显示「正在压缩…消息会排队，压缩结束后发送」。
- 底部状态行对齐 `chat.compactingQueued`：压缩中带排队数（「正在压缩上下文… · N 条排队中」）。
- 补上 `/compact [自定义指令]` 内置命令（与按钮共用 `startCompaction`）：以前手敲 `/compact` 会被当成
  普通提问发给模型；服务端明确拒收时命令文本还给输入框（对齐 pi-web 的 `prependText`）。
- 单测：`CompactionUiStateTest` 补 1 例取消分类（8 个断言）。

**保留的两个有意差异**（均为移动端适配，不影响协议）

- pi-web 用可关闭的 inline alert；手机端沿用 Toast（不占正文空间）。
- 已修复的 per-session 压缩状态仍然保留 —— pi-web 本来就是这个语义（`run.compacting` 属于单条
  会话），App 之前才是那个全局单例。

## [1.8.47] - 2026-11-02

### 🗜️ 修复「一个会话压缩，所有会话都在压缩」与「压缩假失败」，以及重复的未送达提问

**根因（三处叠加）**

1. **压缩状态是全局单例**：`MainActivity` 里只有一个 `var isCompacting`，手动压缩的 POST 在飞时
   切到任何会话，界面都显示「正在压缩上下文...」。服务端真源（`GET /api/agent/{id}` 的
   `isCompacting`）虽然早已解析成 `AgentRuntimeState.isCompacting`，但**没有任何一处读它**；
   SSE 的 `compaction_start/compaction_end` 也被折叠成 `auto_compaction_*`，两个分支都不碰它。
2. **压缩请求是同步长请求**：pi-web 的 `case "compact": await inner.compact(...)` 要等摘要写完才回
   第一个字节，而客户端主 client 的 `readTimeout` 只有 120s。实测同一大会话的四次压缩耗时
   71s / 189s / 296s / 333s（摘要沿用会话 thinkingLevel，`max` 档更慢）—— 超过一半会被判成
   `timeout`，界面报「压缩失败」，服务端却仍在继续并照常写盘成功。
3. **陈旧乐观气泡没有出口**：`SessionSnapshotMerge` 对 `entryId` 为空的 USER 消息只保护、不回收；
   而内容匹配是**从列表头**找第一个同文本气泡，于是压缩开头 `abort()` 丢掉的那次提问会一直留在
   列表尾部，并抢走用户下次同文本提问（「继续」这类）的服务端匹配 —— 新气泡没有归属、被追加到
   末尾，同一条指令就显示两遍；同时 `hasUncommittedUserMessage` 永久为真，本轮永远收不了尾、
   不通知、唤醒锁不放。

**改动**

- 新增 `CompactionUiState`（纯 JVM）：压缩状态按会话维护，真源取「本机请求在飞」∪「服务端在压缩」
  的并集；`compaction_start/end` 不再伪装成 `auto_compaction_*`，手动压缩结束顺便收掉
  `isAgentBusy`（否则要等 60s 启动宽限 + 2 次空闲巡检才会翻回空闲，期间发送被当成「任务执行中」）。
- `compactContext` 改走独立的 10 分钟时限 client；超时/断流不再报「压缩失败」，改为「仍在服务端
  进行，完成后自动同步」，只有服务端真的回了错误（`Compaction failed` / `Already compacted` /
  `Nothing to compact`）才报失败。
- 压缩按钮在转圈期间禁用（`clickable(enabled = !isCompacting)`），避免连点触发 `Already compacted`
  或并发写两条 compaction entry；压缩期间允许普通发送（交给 pi-web 的 park 语义）。
- `SessionSnapshotMerge` 的内容匹配改为**从最新一条往前**找，杜绝陈旧气泡抢走新提问的归属。
- 新增 `PendingPromptSettlement`（纯 JVM）：发送后发起过快照 + 已过 15s 宽限 + 服务端不 running +
  不在队列 → 判定未送达，撤回气泡（列表里没有同文本时才退回输入框，避免用户重发后输入框凭空多一段）。
- 单测：`CompactionUiStateTest`（6 例）、`PendingPromptSettlementTest`（11 例）、
  `SessionTreeNavigationTest` 补 1 例「同文本提问归最新气泡」。

## [1.8.46] - 2026-11-02

### 📁 左侧抽屉底栏增加全局「浏览电脑文件」入口（全盘目录漫游）

1. **左侧抽屉底栏常驻「浏览电脑文件...」入口**：
   - 在左侧抽屉底部操作区（`footer`）新增常驻项，无需进入具体会话，一键呼出全局目录漫游；
   - 支持自由穿透浏览 PC 上的全盘目录与所有驱动器盘符（`C:\`、`D:\`、家目录等）；
   - 支持自动记忆「最近一次浏览的目录」，二次打开秒级就绪。
2. **权威目录校验与安全授权（对齐 pi-web）**：
   - 接入 `POST /api/cwd/validate`，选中目录自动进入服务端 `allowFileRoot` 白名单；
   - 目录切换与远程驱动器盘符（`drives`）解析全量打通，无缝拉起全功能资源管理器 `FilesScreen`。
3. **快照对账与流式状态深度优化**：
   - 完善 `SessionSnapshotMerge`，保护在途流式助手消息的 `streamMessageId`、`mediaList` 与思考耗时，杜绝气泡分裂。

## [1.8.45] - 2026-11-02

> `v1.8.44` 的 tag 因 `build.gradle.kts` 里 `taskGraph.whenReady { it… }` 在 Kotlin DSL 下解析不到
> `it`（脚本编译失败）而没有产出安装包，本版为修复后的同内容首发。


### 🔒 构建配置的两处「静默失败」改成显式失败

- **`-Ppidroid.minify` 拼错不再静默关掉 R8**：原来用 `toBoolean()`，`flase` / `no` / `0` 任何
  非 `true` 的值都会变成 `false` —— 一次手滑就发出去一个没压缩、没混淆的 release 包。
  现在只接受 `true` / `false`，其它值在配置阶段直接报错。
- **`KEY_ALIAS` 不再有硬编码兜底**：以前缺少这个 secret 时会静默用历史别名 `pidroid` 去签，
  报错停在「别名不存在」，把真正的病因藏起来。现在签名材料缺任何一项
  （`keystore/release.jks` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），
  会在**真的要出 release 包**时直接失败并列出缺什么；`testDebugUnitTest` 这类与签名无关的
  构建不受影响（校验挂在 task graph 上，不是配置阶段无条件抛）。

### 🧹 CHANGELOG 去重：6839 → 3543 行

文件里存在两处字节完全相同的重复内容：尾部 2301 行是前面一段的整段副本，另有 28 个版本段
（`1.8.1` ~ `1.8.29`）各出现两次且正文逐字相同。已按「逐段比对、只有完全相同才删」的方式清掉
重复副本，保留唯一一份，版本段落数 249 → 129，且仍严格降序、没有空段、没有格式歪的标题。
发版说明的提取逻辑（`awk` 取第一个匹配段）不受影响。

### 🧭 补记：这几个 tag 没有产出安装包

`v1.8.30`、`v1.8.35`、`v1.8.36`、`v1.8.39`、`v1.8.42` 的 tag 已推送但 CI 未通过（`v1.8.42`
是提交进去的代码编译不过，其余为导入/类型可见性等问题），因此没有 GitHub Release、也没有 APK；
相应内容由紧随其后的版本（`1.8.31` / `1.8.37` / `1.8.40` / `1.8.43`）首发。

## [1.8.43] - 2026-11-02

### 🐛 彻底修复发送消息后气泡被截断丢失与深度思考模型超时误判完成问题

- **根因分析**：
  1. 用户发送消息后，本地列表立即追加乐观气泡，此时服务端尚未将该提问落盘至会话文件；
  2. 会话快照对账（`syncSessionSnapshot`）若在服务端落盘前触发（或思考模型尚未产出 Token），旧的对账合并仅保护流式尾部，对本地已发送的乐观用户消息（`role == USER`）判定为“多余历史”并直接 `removeAt` 截断抹除；
  3. 巡检完成判定中将启动宽限期（`isRecentlyStarted`）和流活跃期（`isSseRecentlyActive`）硬编码为仅 5s 与 6s，而深度思考模型（如 Gemini Flash Thinking / Claude 3.7 Thinking / o1 / R1）思考阶段常达 15–45s，导致等待首个 Token 时被错误提前判定为“已完成”，进而触发截断将提问从界面上抹去（必须退出会话重新打开才能重新拉回）。
- **彻底根治与算法重构**：
  - **`SessionSnapshotMerge.reconcileList` 全量在途保护**：重构快照对账为纯 JVM 对账算法，严密保护本地未落盘的乐观用户消息（`entryId.isNullOrBlank()`）、实时流式助手消息（`isStreaming == true`）与在途工具卡片，未确认落盘前绝不截断；服务端落盘后无缝匹配并回填 `entryId`，彻底告别“发了看不见、重进才能看”的灵异现象。
  - **深度思考模型超时宽限**：将启动保护期放宽至 60s，SSE 流活跃判定放宽至 45s（匹配 pi 30s 心跳周期），并增加未落盘用户提问防呆拦截（`hasUncommittedUserMessage`），杜绝深度思考中途被误判为完成。
- **单测守护**：在 `SessionTreeNavigationTest` 补充针对服务端快照滞后、未落盘提问保护、提问与流式尾部同时在途等关键场景的 3 组完整回归测试。

## [1.8.41] - 2026-11-02

### 🔓 新会话默认权限改为 `full`（PC 端 `defaultTools` 同步钉成同样 7 个内置工具）

之前新会话的权限胶囊默认显示 `configured`。「configured」的语义是**不钉工具清单**，
由 pi 按 `settings.json` 的 `defaultTools` 解析 —— 但本机那份 `settings.json` 里**根本没有
`defaultTools`**，于是 pi 落到自己的缺省 `read / bash / edit / write`（也就是 `default` 档，
`settings-manager.js` 的 `DEFAULT_TOOL_NAMES`）。看起来像「什么都能干」，实际少了
`grep / find / ls`。

本版两处一起改：

| 位置 | 改动 |
| --- | --- |
| `~/.pi/agent/settings.json`（PC，已备份） | 新增 `"defaultTools": ["bash","read","edit","write","grep","find","ls"]` —— 这样 `configured` 在 pi-web 桌面端解析出来的就是完整 7 个内置工具 |
| `AppPreferences.defaultToolPreset` / `ChatScreen.currentToolPreset` | 手机端新会话默认档从 `configured` 改成 `full`，胶囊直接显示 `full`，不会被读成「没配」 |

**这是有意偏离 pi-web 的一处默认值**（上游 #700 明确要求客户端不要偷偷钉死工具清单），
所以代码里把两个前提写进了注释：① PC 端 `settings.json` 必须有同样 7 个内置工具；
② `ToolPreset.fromToolNames(null) → configured` 这条**不能动** —— 服务端没返回 `toolNames`
就是「这条会话没被钉住」，改掉会让所有已存在会话的胶囊在撒谎。

注意：老会话不受影响（胶囊永远以服务端 `toolNames` 为准）；已被显式改过档位的用户
仍沿用自己存的那个值，想立刻生效可在胶囊里重选一次 `full`。

## [1.8.40] - 2026-11-02

### 🎨 代码查看器与资源管理器深度对齐 pi-web：语法着色、行号、自动换行与 @ 引用 (修全屏顶栏覆盖与词法着色)

1. **彻底修复“顶栏错位”问题**：
   - 将全屏文件查看器提升至独立顶层覆盖，打开代码文件时顶栏完全切换为代码专属工具栏，退出后才恢复文件树工具栏。
2. **顶栏功能全量对齐 pi-web**：
   - **相对路径 + 元数据**：显示等宽相对路径与 `language · lines · size`（如 `python · 7 lines · 146 B`）；
   - **实时同步灯**：常驻绿色 🟢 状态圆点；
   - **`@` 引用按钮**：一键将文件以 `@relative/path ` 追加至当前对话草稿输入框，并自动关闭全屏返回聊天；
   - **自动换行开关（wrapLines）**：折行图标秒切“自动换行”与“单行横向平滑滚动”，自动记忆用户偏好；
   - **⤓ 一键下载按钮**：直接流式下载保存至手机 `Download/πDroid/` 目录；
   - **`⋮` 更多菜单**：提供一键复制全文、复制路径、外部应用打开、重新加载。
3. **原生轻量语法着色与行号列**：
   - 新增零依赖纯 Kotlin 词法分词器（`CodeSyntaxHighlighter`），支持 Python, YAML, Kotlin, JS/TS, JSON, Bash 等语言高亮；
   - 等宽右对齐固定行号，严格接入当前主题语义色，过 CI 裸色门禁，自带 1000 行大文件性能降级保护。
4. **目录树 `@` 引用**：
   - 资源管理器文件/目录长按菜单增加「@ 引用到当前对话」，目录自动带 `/`，含空格自动包双引号。

## [1.8.38] - 2026-11-02

### 🔄 状态栏通知图标升级后不刷新

1.8.28 已经把 `setSmallIcon` 全部换成了品牌 π，但装上新版本后通知栏那条常驻通知仍可能顶着旧图标。
两个原因叠在一起：

1. 覆盖安装只会杀掉进程，**不会**撤掉 `setOngoing(true)` 的常驻通知 —— 旧通知会一直挂在通知栏里；
2. 同一个通知 ID 再次 `notify` 时，部分 SystemUI 会沿用缓存下来的图标位图。

本版在 `PiForegroundService` 里记一份「上次刷新图标时的 App 版本」：版本一变就先 `cancel`
旧通知再重建，让新图标真正生效。版本没变时这条路径不走，不会多一次闪烁。

> 若升级后状态栏还是旧样子：把「网关常驻保活」关掉再打开一次，或重启手机 —— 系统会重建通知。

## [1.8.37] - 2026-11-02

### 📁 完整移植 pi-web 文件管理能力：资源管理器、多格式预览与手机下载 (修复文件名净化单测断言)

- 增强 `FileDownloader.sanitizeFileName`：非法字符转下划线后去除首尾冗余下划线，全非法字符安全回退默认文件名；
- 单元测试 362 项全部通过，全流程对齐上游协议；
- 通过 CI `Guard hardcoded colors` 门禁。

## [1.8.34] - 2026-11-02

### ✨ 提示词增强可以指定专用模型（质量瓶颈在模型，不在提示词）

「再接近 WorkBuddy 就值得做」——我先做了两层验证，结论和直觉不一样。

**第一层：加「意图识别」层 —— 验证失败，没有采纳。**
设想让模型先判断指令属于排错 / 做功能 / 咨询，再按那类的专家关注点补充。
实测四个场景（专家特征命中数，越高越像专家）：

| 场景 | 现状 pe-5 | 加意图层 |
| --- | --- | --- |
| 排错 | 1/6 | 1/6 |
| 做功能 | 0/7 | 1/7 |
| 咨询 | **2/6** | **0/6（变差）** |
| 复杂任务 | 1/6 | 3/6 |

只有复杂任务有改善，咨询场景反而更差，整体变长 20~40%。原因是「三类描述」写进提示词
又变成清单效应——模型想每类都沾一点。**所以没合进去。**

**第二层：换模型 —— 差异明显，采纳。**

同一套 pe-5 提示词、同一条输入（「帮我把这个报错修一下」，附编译错误工具结果）：

- `deepseek-flash`：`assembleDebug 编译在 ChatScreen.kt 第 1841 行报 unresolved reference…帮我修一下`
- `claude-opus-4-6-thinking`：`…帮我查一下这个符号是在哪里定义的（或者应该定义在哪里），然后补上缺失的声明或引用，让 assembleDebug 能编译通过`

差别不是"写得多"，而是**强模型会给诊断路径**（先查定义在哪，再补），flash 级只给动作。
功能类场景同理：opus 会补出「用现有偏好存储存下来、Compose Switch 展示、默认关闭」这条实现链。

**所以本版把选择权交给你，而不是继续在提示词里堆技巧。**

- 新增「设置 → 外观与反馈 → 增强模型」：默认「跟随当前会话」（行为与以前完全一致），
  可选任一已配置模型作为增强专用模型；
- 当前选择直接显示在设置行上（provider/model），点「更改」随时换回跟随会话；
- 增强请求走选定模型，其余逻辑（占位符保护、过期丢弃、撤销、预算）一律不变。

**模型可用性实测**（这台机器当前状态，会随账户变化）：
`deepseek-flash` ✅、`claude-opus-4-6-thinking` ✅、`gemini-3.8-flash-high` ✅；
`gpt-6-sol` / `gpt-6-luna` ❌ INSUFFICIENT_BALANCE，`qwen3.8-flash` ❌ Access denied。

强模型更慢（opus 实测 4.8~7.6s，flash 2.8~3.7s）、成本更高，这部分需要你自己权衡。

---

## [1.8.33] - 2026-11-02

### 📁 完整移植 pi-web 文件管理能力：资源管理器、多格式预览与手机下载

严格对齐 `@agegr/pi-web@0.13.0` 协议与行为，打通三类移动端文件使用场景：

1. **以资源管理器方式浏览/操作文件**：
   - 工作区长按菜单一键进入「浏览文件」全屏树视图；
   - 目录逐级懒加载，内置 20 深度 LRU 展开记忆（`ExpandedPathsMemory`）；
   - 支持隐藏文件（.gitignore）三态开关，内置大目录截断保护（防大文件夹卡顿）；
   - 毫秒级 150ms 防抖文件索引搜索（`/api/file-index?cwd=&q=`）并自动折叠为目录树；
   - Git 变更文件徽标与目录红点追踪。

2. **在页面内直接打开远端文档/图片预览**：
   - 抽象通用 `RemoteFilePreviewLoader`，解耦聊天与文件管理器的预览链路；
   - 支持 TEXT / MARKDOWN / IMAGE / SVG / DOCX / AUDIO / VIDEO 分派；
   - 图片预览走 `type=download` 避免 413 限制，全链路配合 `decodeSampledImage` 防 OOM；
   - 新增原生 `PdfDocumentView` 支持 PDF 逐页翻页预览与高分辨率分页渲染。

3. **安全流式下载到手机**：
   - 新增 `FileDownloader`：采用 64KB 缓冲区流式写入公共下载目录（`Download/πDroid`），杜绝大文件 OOM；
   - Android 10+ 适配 `MediaStore.Downloads` 与两段式 `IS_PENDING`；Android 9 优雅回退；
   - 严格遵循伴侣网关 Bearer 鉴权通道。

## [1.8.32] - 2026-11-01

### ✨ 提示词增强去掉「六段式模板」，改为像人一样说话（pe-5）

用户反馈"像固定模板去套，有时很罗嗦，内容太多反而容易跑偏"。量化之后确实如此：
**10 个字的输入被扩成 635 字（63 倍）**，连"设置页加个开关"这种 7 字需求也套了 7 个小节。

根因是我把方向搞反了。WorkBuddy 公开的说法是「把原始诉求改写得更干净、更聚焦、更少废话」，
而我在提示词里把【目标】【输入】【动作】【约束】【输出】【验收】六个小节完整列了出来。
模型的已知行为是**「枚举即清单，清单即填满」**——写着"按需取用"也没用，它会逐个填满，
于是每条结果都变成一份分析报告，还附带「验收标准」「逐项核对」「【待确认】」。

pe-5 的改法是做减法：

- **删掉小节枚举**，明确"不要写【目标】【输入】【动作】这类小标题——那是分析报告，
  不是用户会打在输入框里的话"；
- **用长度锚点替代字数硬限**：通常为原稿的 1.5~3 倍、上限 5 倍；用户打 10 字就写 20~40 字，
  真复杂（多处改动、要澄清前提）才到 300~500 字，靠信息密度而不是靠结构撑起来；
- **禁止元话术**：不写验收标准、交付清单、检查项、逐项核对；把【待确认】清单降级成一句话
  （"不确定就先按你的判断来，并告诉我你选了什么"）；
- **保留全部既有能力**：context 的 role 语义（user / assistant / tool / summary）、
  材料里已有的事实（文件名、符号、行号、报错原文、技术栈、项目约定）照实用，
  用户的否定与审批约束原样保留。

### 📊 同一批输入的前后对比

| 原稿 | pe-4 | pe-5 |
| --- | --- | --- |
| 设置页加个开关（7 字） | 480 字 · 7 小节 · 3 处元话术 | **109 字 · 0 小节 · 0 元话术** |
| 帮我把这个报错修一下（10 字） | 719 字 · 7 小节 · 3 处 | **142 字 · 0 小节 · 0 处** |
| 为什么 SSE 老是断（19 字） | 573 字 · 6 小节 · 2 处 | **136 字 · 0 小节 · 0 处** |
| 上传改异步，先给方案别改代码（16 字） | 659 字 · 7 小节 · 3 处 | **136 字 · 0 小节 · 0 处** |

边界也验证过：37 字的复杂需求（网络层异步化 + 重试超时统一）仍写成 145 字，把
`NetworkClient.kt`、`UploadRepository.kt:18` 这些工具结果里的事实带上了；91 字的长原稿
只补了必要细节（2.5 倍），没有画蛇添足。

服务端 88 项测试通过（新增一条回归：提示词不得再出现带解释的小节模板）。

---

## [1.8.31] - 2026-11-01

> `v1.8.30` 的 tag 因工具定义面板里把 `remember` 写进了 `LazyColumn` 的非 @Composable content 而编译失败、没有安装包，本版为修复后的同内容首发。


### 🛡 代码评审修掉的两个真问题

**一、全屏看图会 OOM（高危）**：卡片解码早就在用 `decodeSampledImage` 降采样，但
`FullscreenMediaLightboxDialog` 里仍是全尺寸 `BitmapFactory.decodeByteArray` —— 一张
4000×3000 的 CAD 出图约占 48MB 堆，正是这个仓库赔过一次的「一打开大图就闪退」。
现在查看器按屏幕长边 ×2（`MediaLoadPolicy` 的既有口径）降采样，缩放到 6× 仍够看清。

**二、泄露密钥的护栏会静默失效（安全）**：`Refuse the compromised keystore` 原来只在
「解析出指纹且**正好等于**泄露那把」时才红。keytool 失败、密码错、输出格式变化或大小写不同，
`FP` 就是空的或对不上，护栏照样放行。现在指纹解析不出来直接让流水线失败（失败即关闭），
比较前两边都归一化成小写去分隔符；顺带把取指纹的方式从 `awk -F': '` 换成对空格更宽容的 `sed`。

### 🧹 评审里顺手修的小问题

- 工具定义面板：`LazyColumn` 的 key 用工具名，服务端一旦给重名就会抛
  `IllegalArgumentException`（整块白屏），改为先 `distinctBy`；卡片底色从 `surfaceContainer`
  换成 `background` —— 本行参数用的是 `textMuted`，而 `ColorContrastTest` 只把
  「textMuted 画在 background / surfaceContainerLow」算达标（3.72:1），换到
  `surfaceContainer` 会掉到 3.3–3.4:1。
- 工具卡的策略拒绝提示改用 `remember(tool.isError, tool.output)` 缓存：`explain()` 是几次全文
  扫描，几十 KB 的工具输出在流式期间每帧重算一次并不便宜。
- 工具定义面板在会话消失（新建对话/会话被重建）时自动关闭，不再留在「已开启」状态等下一个
  会话出现时突然弹出。

## [1.8.29] - 2026-11-01

### 🎨 配色收尾：业务组件裸色真正清零 + 首次加 CI 守卫

v1.8.21 宣告"裸色只剩有意保留的 20 处"，逐字核查后发现口径不准：
其中 3 处是 PDF 错误红（未收口）、1 处是行内代码底纹、1 处是注释文字。这一版真正清零，并把标准写进 CI。

#### 已修

| 项 | 原状 | 现状 |
|---|---|---|
| **PDF 徽标 / 图标** | `#EF4444` 压在 16% 叠色底上，10sp 文字只有 **3.05:1**（不达 AA），且与主题 error 是两套红 | M3 `errorContainer` + `error`：浅 **5.30:1** / 深 **5.84:1** |
| 行内代码底纹 | `Color(0x1F88888F)` 裸值 | `ThemePalette.InlineCodeTint` |
| 脑图分支色 / 图表色（11 处） | 裸值、无归属说明 | `ThemePalette.Chart*` 七个命名常量，注明"数据色、不随主题" |
| 扫码沉浸层（4 处） | 裸值；`ThemePalette` 注释只说有 git/图表色，没提 scrim | `ScrimBase / ScrimLightbox / ScrimSurface / ScrimOnSurface`（实测 16.02 / 14.64:1） |
| 注释里的 `Color(0xFF8B5CF6)` 字样 | 会让逐字核查误判为未收口 | 改为描述性文字 |

#### 主题层清账

删除 10 个从未被引用的 `Termius*` 常量、`TermiusColorScheme`，以及 `LocalTextPrimary/Secondary/Muted`
三个 **0 消费者**的 `CompositionLocal`；`error` 仍只有 M3 `ColorScheme` 一处真源。

新增 `docs/DESIGN-color-tokens.md`：三层结构、**承接面约束表**（`textMuted` 为何只允许
`background`/`surfaceContainerLow`）、组件级 Token 归属（哪些刻意不随主题）、三处有意偏离、门禁说明。

#### 工程

- **CI 新增 `Guard hardcoded colors` 步骤**：`app/src/main/java` 下除 `ui/theme/` 外一旦出现
  `Color(0x......)` 直接判红。配色收口从此有机制兜底。
- `ColorContrastTest` 增至 **17 组**（新增沉浸层断言）。
- 10 处 `Icons.Filled.ArrowBack/KeyboardReturn/Undo/PlaylistAdd/CallSplit` 换成
  `Icons.AutoMirrored.Filled.*`（RTL 语言下方向才正确），编译警告清零。

## [1.8.28] - 2026-11-01

### 🔔 通知栏和 App 内的图标也换成 π

1.8.24 / 1.8.25 只换了桌面图标：常驻通知与「任务完成」通知用的还是系统自带的
`stat_notify_sync`（一个同步箭头），App 内 logo 的 π 也偏瘦。

#### ① 通知栏换品牌 π

新增 `res/drawable/ic_stat_pi.xml`：24dp 画布、字形占 75%、纯白单色、**不带底块**。
通知小图标只取 alpha 通道、由系统整体着色，带底色块会在状态栏上变成一个实心方块。
四处 `setSmallIcon`（前台常驻服务 ×2、任务完成通知 ×2）全部换成它。

#### ② App 内 logo 补满一点

`ic_pi_glyph.xml` 的字形从占画布 48% 提到 **54%**：28dp 的「关于」行上原来四周留白过多，
π 看着虚。

三份矢量现在的分工（比例不能互换）：

| 用在哪 | 文件 | 字形 / 占比 |
|---|---|---|
| 桌面图标 | `ic_launcher_foreground.xml` | 36 × 32.8 · 可见区 50% |
| App 内 logo | `ic_pi_glyph.xml` | 58.3 × 53.1 · 画布 54% |
| 通知栏 | `ic_stat_pi.xml` | 18 × 16.4 · 画布 75%（单色无底） |

## [1.8.27] - 2026-11-01

> `v1.8.26` 的 tag 因工具定义解析器里两个可空接收者调用被编译器拒绝而没出安装包，本版为修复后的同内容首发。

### 📋 权限面板补上「这一档到底装进了哪些工具」

权限胶囊以前只说「当前是 read-only」，但用户看不到这一档实际装进了什么 —— 尤其内置工具之外
还有 MCP/扩展工具时，「为什么这个命令能跑、那个不能」就没有答案。现在权限面板底部多了一行
**查看工具定义…**，列出本会话每条工具的启用状态（已启用 / 未启用 / 脚本内）、描述与参数摘要。

数据走 pi 的 `get_tools`（`POST /api/agent/{id}`），与 pi-web 的 Tool definitions 同源：
`declarationHidden`（codemode 的 "only" 模式下启用但请求不带声明）单独标成「脚本内」，
不和「未启用」混为一谈。新建对话还没有会话可问，入口不出现。

### 🧭 服务端「策略性拒绝」翻译成人话

三条拒绝原文都是英文长句，以前会原样糊在工具卡或 toast 里：

| 原文 | 现在 |
| --- | --- |
| `Cannot change tools while the session is running` | 任务运行中不能切换权限模式，等本轮结束再切 |
| `Subagent tool selection is fixed by its profile` | 子代理的工具由它自己的 agent 配置决定，手机上不能改 |
| `... read-only tool selection ... does not mark ... read-only (readOnlyHint) ...` | 本会话是只读权限：只放行服务端标记为只读的 MCP 工具，要写操作先切到 default 或 full |

映射收在 `ToolErrorMessages` 一处，切权限失败的 toast 与聊天里工具卡的错误提示共用；
不认识的服务端错误仍然原样展示，不猜。

### 🔢 不再编造 MCP 工具数量

新添加的 MCP 服务以前在本地先写死「1 个工具」，随后服务端刷新失败时这个假数字会一直留着。
现在本地只写已知信息（0），真实数量等服务端回读；添加失败也不再报「已更新」而是明说没生效。

### ✅ 单元测试

`ToolDefinitionsParsingTest`（RPC 包装层、裸数组、`declarationHidden`、错误响应不当空清单）
与 `ToolErrorMessagesTest`（三条拒绝 + 未知错误原样放行）。

## [1.8.25] - 2026-11-01

### 🩹 桌面图标「巨字」修复：前景层按可见区定比例，不再按 108 画布

1.8.24 的图标在预览里看着正好，装到桌面上却几乎撑满整块、衬脚贴边。

根因是自适应图标的 108×108 画布**只有中心 72×72 会被显示**，其余 36 被系统遮罩吃掉 ——
等于把内容放大约 1.5 倍再裁切。1.8.24 按「占 108 画布 48%」画字形，真机上就变成
占可见区 72% 的巨字，同时衬脚也顶到了圆角边缘。

拆成两份几何同源、比例不同的矢量：

| 用途 | 文件 | 字形尺寸 | 实际视觉占比 |
|---|---|---|---|
| 桌面图标前景 | `ic_launcher_foreground.xml` | 36 × 32.8 | 可见区的 50% |
| App 内 logo | `ic_pi_glyph.xml` | 51.7 × 47.1 | 整个画布的 48%（无裁切） |

两个文件不能再合回一个：桌面走的是「放大 1.5 倍 + 裁切」，App 内走的是「整画布直出」，
同一份比例不可能同时合适。改字形时两处一起改。

## [1.8.24] - 2026-11-01

### 🏷️ 正式更名为 πDroid，图标换成字母 π

品牌名从 `piDroid` 改为 **πDroid**；桌面图标从「三块圆角矩形拼出来的 π」换成一条连续轮廓的
**衬线字母 π**（横梁两端下折、竖腿带衬脚）。

#### ① 名字一次改全

App 名、桌面图标、前台服务通知标题、扫码页配对码提示、会话列表工作区回退名、设置页「关于」行、
生成式 UI 导出目录（`Pictures/πDroid`、`Download/πDroid`）全部换新名。

`app_name` 统一收进 `res/values/strings.xml`，Manifest 不再硬编码 —— 此前 `strings.xml` 写 `PiDroid`、
`AndroidManifest` 写 `piDroid`，两处大小写还不一致，改一处就漏一处。

#### ② 图标是单一路径的矢量

新字形是 `res/drawable/ic_pi_glyph.xml`（108 视口、字形 51.7×47.1、对角半径 34.9），
落在自适应图标 72dp 圆形遮罩的安全区内，圆形裁切不会切掉衬脚。同时补上 Android 13 的
**主题图标**（`<monochrome>`）：此前缺这一项，系统着色时图标会被当成普通位图缩成小方块。

App 内 logo 直接复用同一份矢量（tint 取实际底色的最佳前景，仍是 14.6:1），不再单独手绘路径 ——
以后调字形只改一处。

#### ③ 机器标识特意没改名

这些地方保留 ASCII `piDroid`，改了会真的坏掉：

| 位置 | 改了会怎样 |
|---|---|
| `AppUpdateManager.GITHUB_REPO_NAME` | 仓库仍叫 `codywon/piDroid`，改名后 `releases/latest` 直接 404，自动更新断掉 |
| `User-Agent` | HTTP 头只允许 ISO-8859-1，塞 π 会抛异常 |
| APK 资产名 `piDroid-<ver>.apk` | 下载走 URL path，镜像站对非 ASCII 文件名不稳定 |
| wake lock tag / prefs key / 更新临时文件名 | 内部标识，改了丢「忽略此版本」记录 |

历史导出的图片与 SVG 仍在旧目录 `Pictures/piDroid`、`Download/piDroid`，不会自己搬家。

### 🧩 补齐 1.8.23 漏提交的分叉响应解析

`PiApiClient` 里引用的 `ForkSessionResponseParsing` 在 1.8.23 提交时漏了入库（tag `v1.8.23`
因此编译失败、没有安装包），本版一并补上源码与回归测试。

本版安装包同时首发 `1.8.23` 里的另外三项改动（工具权限模式对齐、代码模式开关改走真接口、
删除从未存在的「人工审批」链路），详见下方 `## [1.8.23]` 一节。

## [1.8.23] - 2026-11-01

### 🔧 工具权限模式真正对上 pi-web（此前只有界面，没有真相）

「工具权限模式」的接口本来就用对了（`POST /api/agent/{id}` 的 `set_tools`），但只要关掉 App 重进，
权限胶囊显示的永远是**本机上次选的那个值**，而不是这条会话实际钉住的工具 —— 于是会出现
「界面写着 full，会话其实还在跟随 settings.json」这种看起来没问题的错觉。本版把这条链路补齐：

- 打开会话时用服务端返回的 `toolNames` 反推预设（`null` = 跟随 settings.json、空清单 = 仅聊天），
  每次对账（SSE 事件与状态轮询）都重新对齐；本机偏好只用于**新建会话**，默认值改为 `configured`；
- 新建会话把当前预设一起交给 `/api/agent/new`，不再出现「新建就显示 full、服务端其实没钉住」；
- 预设判定逐行对齐上游 `lib/tool-presets.ts`：只认 7 个内置工具名、`powershell` 归一成 `bash`、
  排序后比较、认不出来回落 `default`（以前默认回落的 `full` 更危险）；未知偏好值一律回落 `configured`；
- 切权限被服务端重建会话（跨过「仅聊天」边界必然重建）时，重新挂 SSE、清掉排队/阶段/斜杠命令缓存，
  再拉一次快照回填真实状态；切换失败会把胶囊恢复成会话真实值，不再停在一个没生效的预设上。

### 🐞 代码模式（Codemode）开关其实一直是假的

三个旋钮以前发给 `POST /api/mcp {action:"set-codemode"}` —— 上游**从来没有这个 action**，
所以每次都是静默失败，界面却照样弹「已保存」。现在读写都走真正的
`GET/PUT /api/tools/settings`（`codemode` / `codemodeMode` / `codemodeInlineBudget`，一次只提交一项），
失败会明说「代码模式未生效：原因」并把开关回滚到服务端的值；「关闭」这个上游不存在的档位已去掉。

### 🗑 删掉从未存在的「人工审批」链路

App 里监听 `approval_required` 事件、画「待审批」卡片、发高优先级通知、再 `POST /api/agent/{id}/approval` ——
pi-web（0.12.2 / 0.13.0）与 pi SDK 都没有这条事件与这个路由，点「批准」只会 404 且结果被丢弃。
整条链路（事件分支、卡片 UI、震动与通知函数、客户端方法）已移除；工具权限完全由会话预设决定。

### ✅ 单元测试

新增 `ToolPresetTest`、`ToolSettingsChangeTest`、`ToolSettingsJsonTest`：
覆盖预设反推、`powershell` 归一、非内置工具不参与判定、未知值回落、
以及 `PUT /api/tools/settings` 的「一次一项」报文与「写入裸值 / 读回包装对象」的形状差异。

### 🐛 侧边栏「分叉 / Clone」恢复可用：不再 415

侧边栏长按分叉必然失败，弹的是 `分叉失败：Server HTTP 415: {"error":"Content-Type must be
application/json","code":"request-denied"}`。

**原因**：pi-web 的 `POST /api/sessions/[id]/fork` 在解析 `params` 之前就先跑
`hasJsonContentType()`（`lib/request-security.ts`），只要请求头不是 `application/json`
或 `application/*+json` 就直接 415 —— 它其实**根本不读 body**。而 App 这边发的是
`"".toRequestBody(null)`：空 body 且不设 Content-Type，于是被稳稳拒在门口。
本机实测对 30141 验证过：不带 Content-Type → 415；改成 `application/json` + `{}` →
越过守卫进到业务层（返回 404 `Session not found`）。

现在与 pi-web 前端一致：`Content-Type: application/json` + body `{}`。

**顺带对齐响应结构**：0.13 起返回的是 `{ sessionId, session }`，新会话的 `path` / `name`
已经挪进 `session`（SessionInfo）里，顶层没有了。旧代码只读顶层 → 两者恒为 null，
分叉出来的会话在手机上被命名成「源标题 · 分叉」，刷新后又被服务端的「源标题 · 3f9c」
覆盖，看着像丢了东西。解析现在优先读 `session`、顶层做兜底（兼容老网关），并抽成纯逻辑
`ForkSessionResponseParsing`，附 6 组 JVM 单测（新形状 / 旧形状 / 无名源会话 / 空串回退 /
缺 sessionId / 非对象响应）。

消息级「新会话」分叉（`POST /api/agent/{id}` `{type:"fork"}`）不受影响：那条路由不校验
Content-Type，本来就发 JSON。

## [1.8.22] - 2026-11-01

### ⚡ 预算改为「效果优先」：上下文 12000→40000 字，输出 2400→4096 token

之前为了让请求快、省钱，把预算压得很紧（上下文 6000~12000 字、输出 1600~2400 token）。
问题是我们面对的是 **1M 上下文的模型**，预算却比模型能力低一两个数量级 —— 模型只能看到
一小段历史，然后在信息真空里扩写，这才是「像 demo」和「偶发无效输出」的共同根源。

本版按「够用且安全」重新定值：

| 项 | 之前 | 现在 |
| --- | --- | --- |
| 参考上下文总量 | 12,000 字 | **40,000 字**（≈27k tokens，占 1M 窗口 3%、200k 窗口 13%） |
| 单条消息上限 | 1,200 字 | **4,000 字**（够放一段完整堆栈或一个函数实现） |
| 上下文档数 | 12 条 | **24 条** |
| 客户端候选窗口 | 60 条 | 120 条 |
| 草稿上限 | 6,000 字 | 12,000 字 |
| 输出 token | 2,400 | **4,096**（约 2500~3000 中文字，不再担心撞 length 被判定无效） |
| 输出字符上限 | 10,000 | 20,000 |
| 超时 | 服务端 30s / 客户端 35s | **服务端 50s / 客户端 55s** |

提示词里的篇幅分档也一并放宽：不再写死「250~450 字」，改为「由任务需要决定，
复杂任务充分展开到 800~1200 字也没有问题」。

### 🩺 顺带修好「看不到原因」

- `INVALID_OUTPUT` 一族（输出被截断 / 改动了受保护片段 / 为空 / 套了代码围栏）
  以前在手机上一律显示「增强结果不可用」，把四种完全不同的原因压成了一句话。
  现在透出服务端的具体说明，用户与排查都能区分；
- 网关日志在失败时追加 `code=`（只记错误码与稳定文案，绝不打印草稿内容）。

### ✅ 实测（40k 字预算下的真实规模上下文）

一条 4000 字方案 + 60 行编译错误工具结果 + 项目约定摘要：

```
[13062ms] OK 828ch   ✓Dispatchers.IO ✓Mutex ✓StateFlow ✓最小侵入 ✓ChatScreen.kt
[ 5622ms] OK 1205ch  ✓Dispatchers.IO ✓Mutex ✓StateFlow ✓最小侵入 ✓ChatScreen.kt
[ 9128ms] OK 966ch   ✓Dispatchers.IO ✓Mutex ✓StateFlow ✓最小侵入 ✓ChatScreen.kt
```

三例全部把上一轮方案里的五个关键决策逐条落进结果，并把项目早期约定与工具结果中的
`ChatScreen.kt` 一并带上——此前这些几乎都看不到。P50 9.1s / max 13.1s，50s 超时余量充足。

服务端 87 项测试通过。

---

## [1.8.21] - 2026-11-01

### 🎨 收尾：业务组件不再有裸色值（149 处 → 0）

前两版只改到「主题层 + 3 个弹窗」，会话列表、聊天流、设置页、Git 视图里仍散落着
149 处写死的十六进制色。这一版全部收敛成语义 Token，浅色/深色自动适配。

#### 你最容易看到的三处

| 位置 | 原来 | 现在 |
|---|---|---|
| **用户消息气泡** | 写死 `#232C3F` / `#EFF6FF`，换到琥珀或翡翠主题仍是蓝底 | 跟随主题的 `primaryContainer`，文字对比度 9.03~13.89:1 |
| **Markdown 提示块**（TIP/IMPORTANT/WARNING/CAUTION/NOTE） | 5 个颜色深浅共用，深色模式只有 3.0~3.3:1 | 5 个语义色分深浅：TIP 5.02/9.93、IMPORTANT 5.70/6.36、WARNING 7.09/10.36、CAUTION 6.47/6.25、NOTE 5.19/6.85 |
| **会话列表状态** | 等待确认 `#F59E0B`(白底 2.16)、未读 `#0891B2`(3.68)、置顶图钉 `#EAB308`(**1.92**) | 语义 warning(7.09)、新增 info `#0E7490`(5.36)、新增 pin `#D97706`(3.19，图标档 ≥3:1) |

#### 其它一并收敛

- **浮动按钮 / 确认按钮**前景改 `theme.onPrimary`（琥珀主题下白字只有 1.67:1）
- **删除类按钮**统一 `colorScheme.error` + `colorScheme.onError`（原来 8 种红：`#CF222E`/`#DC2626`/`#EF4444`/`#EF5350`/`#F85149`/`#991B1B`/`#DF2A39`/`#F87171`）
- **设置页**：连接状态、Vision 标签、删除项、更新提示等 32 处裸色 → `success`/`warning`/`error`
- **Git 视图**：增删/修改标记、diff banner、词级高亮，11+12 处 → 语义色 + `ThemePalette` 常量
- **技能面板**作用域徽标：原写死紫/蓝 + 深色专用浅字，浅色下几乎读不出 → 语义色
- 阈值提示、缓存率、模型能力图标、置顶区高亮等全部走 Token

#### 现状

业务组件里 `Color(0x...)` 当时只剩 20 处，但**并非全部有意保留**：11 处是图表分类色、5 处是沉浸底、
1 处是行内代码底纹、3 处是 PDF 错误红（未收口）、1 处是注释文字。真正清零见 v1.8.29。

`ColorContrastTest` 增至 16 组：新增 info / pin / 成功按钮前景 / 用户气泡四项断言。

## [1.8.20] - 2026-11-01

### 🎨 浅色模式：代码块不再是一块黑砖

1.8.19 修的是主题层（弱化文本、星标、按钮前景），但浅色模式下**看得见**的三个问题一直没动。

#### ① 代码块 / Diff 块支持浅色（变化最大）

浅色模式下代码块此前强制使用 Catppuccin 暗底 `#181825`，在白色对话流里是一块
**17.6:1 明度断层**的黑砖。现在按主题分支：

| | 底色 | 表头 | 正文 |
|---|---|---|---|
| 浅色 | `#F6F8FA` | `#EAECEF` | `#1F2328`（**14.84:1**） |
| 深色 | `#181825` | `#11111B` | `#CDD6F4`（12.14:1） |

Diff 行同步双模：浅色用 Primer 实底实字（新增 `#1A7F37`/`#E6FFEC` 4.81:1、删除
`#CF222E`/`#FFEBE9` 4.67:1、hunk `#0969DA`/`#DDF4FF` 4.56:1），深色保持原有的半透明叠色。

#### ② 流速徽章的数字终于读得清

`t/s` 徽章原来是固定白字压亮色底：黄 **1.64:1**、绿 **2.01:1**、青 **2.42:1**，全程都在流式回答里显示。
现在色相一个不改（仍对齐 pi-web 阈值），前景按底色亮度取白或深墨：

| 档位 | 底色 | 前景 | 对比度 |
|---|---|---|---|
| ≥50 t/s | `#53B3CB` | 深墨 | **8.00:1** |
| ≥30 t/s | `#9BC53D` | 深墨 | **9.63:1** |
| ≥15 t/s | `#F9C22E` | 深墨 | **11.79:1** |
| <15 t/s | `#E01A4F` | 白 | **4.74:1** |

#### ③ 行内链接跟随主题

Markdown 行内链接原来写死 `#3B82F6`（Tailwind blue-500），白底只有 **3.68:1**，
而且在琥珀/翡翠主题下依然是很跳的蓝色。现在改为 `theme.primary`：

| 主题 | 链接对比度 |
|---|---|
| 纯白明眸 | 5.19:1 |
| 极客冰蓝 | 7.98:1 |
| 黑曜石翡翠 | 10.12:1 |
| 复古琥珀 | 10.36:1 |
| 暗夜极客 | 6.85:1 |

#### 工程侧

`ColorContrastTest` 由 11 组扩到 15 组，新增：链接跟随主题、代码块双模、Diff 行、
流速徽章四档的对比度断言。色值全部落到 `ThemePalette`，业务组件不再出现裸 `Color(0x...)`。

## [1.8.19] - 2026-11-01

### 🎨 色彩系统重构（A 方案：只修"看不清"，不动观感）

首版把卡片发丝线按"控件边界"的门槛调重、又把弱化文本推到接近二级文本的亮度，结果层次被压平、
整屏变吵。这一版回到保守方案：**线条与文字层次维持原样，只保留真正的可读性修复**。

#### 弱化文本：清楚一点，但仍明显退在后面

会话卡片的时间戳、消息条数、工作区名这类灰字，原来几乎读不出来：

| | 原值 | 本版 |
|---|---|---|
| 浅色（纯白明眸） | `#8C959F` 3.04:1 | `#7D8590` **3.73:1** |
| 深色（其余四套） | `#484F58` 2.09:1 | `#6E7681` **3.77:1** |

关键是**没有**推到 4.5:1：那样它会和二级文本（6.4:1 / 5.6:1）几乎同亮，层次分离度从
2.10×/2.69× 塌到 1.22×/1.21×，看起来就像"所有字一样重"。现在分离度是 **1.71×/1.49×**，
一眼仍能分出主次。

> 这些是元数据而不是正文，Apple 的 `tertiaryLabel` 同样故意低于 AA。这一取舍已写进
> `ColorContrastTest`：弱化文本锁 ≥3.0:1，同时锁"二级 ÷ 弱化 ≥1.45×"。

#### 卡片线条：恢复原值

`#E1E6EB`（浅）/ `#30363D`（暗夜）保持不动。WCAG 1.4.11 管的是"理解控件所必需"的边界，
卡片靠背景落差与排版区分，装饰性发丝线不在其中；主流体系也都刻意很轻
（Primer 1.45:1、Tailwind slate-200 1.23:1、M3 outlineVariant 1.21:1）。

#### 保留的修复（不改变整体质感）

- **主按钮 / 浮动按钮不再"失明"**：前景按主色实际亮度取白或深墨——琥珀 1.67:1 → **11.60**、
  翡翠 1.70 → **11.40**、冰蓝 2.14 → **9.04**、暗夜 2.53 → **7.66**（浅色仍是白字 5.19）
- **默认星标**：浅色 `#F59E0B`(2.16:1) → `#B45309`(**5.02:1**)；空心星从 `textMuted` 22% alpha
  （约 1.4:1）改为实色（3.04 / 3.77:1，可点击控件需 3:1）
- **深度推理 / 高思考**：3 种散落黄（`#F59E0B`/`#D97706`/`#D29922`）收敛为语义 warning
  （浅 7.09:1 / 深 10.25:1）
- **上下文进度条**：`#CF222E`/`#D29922`（白底 2.55:1）→ M3 `error` / 语义 `warning`
- **error 四个槽位单一真源**：原来深色用 `TermiusRed`、浅色用 `#CF222E`，且
  `onError`/`errorContainer`/`onErrorContainer` 未定义
- **logo 的 π**：不再由 `theme.primary` 推导（那会在 3 套深色主题下变成近黑字压在深色块上，
  约 1.3:1），改由实际底块色算出 **14.6:1**；logo 本身不随主题变色
- **冷启动**：默认浅色主题却把 `windowBackground` 写死深色 `#0D1117`，现在浅色在 `values/`、
  深色在新增的 `values-night/`

#### 工程侧

- `ArgbContrast`：全工程唯一的 WCAG 对比度实现，生产与单测共用（`Int` ARGB 入参，单测零 Android 依赖）
- `ThemePalette`：所有原始色值收敛成纯 Kotlin 色板，`AppTheme` 只做语义映射（对外签名未改）
- `ColorContrastTest`：11 组断言覆盖 5 套主题，新增**层次分离度**门槛，防止再出现
  "每个 Token 都合规、合起来变丑"

## [1.8.18] - 2026-11-01

### 🐛 左侧栏长按拖动排序不再卡顿跳动

拖动排序此前有三个叠加的问题，一拖就「一顿一跳」：

1. **每跨过半行就发一次请求**：旧实现把「位移超过半个行高」直接当成一次相邻移动，
   立刻 `move-project` 并回读 `ui-state`；服务端回来的新顺序又把列表重排一遍，
   手指下的那一行于是在两个位置之间来回跳。现在拖动期间只重排本地展示顺序
   （`wsOrderOverride`），**松手时才提交一次**，回包只在顺序到齐后接管。
2. **换位后位移读数被归零**：行一旦换位，它的格子已经上/下移了一整行，读数归零等于让这行
   瞬间回弹半行。现在换位后把读数减去「邻居行高 + 行间距」，视觉位置保持不变，
   跟手连续；松手时残位移再补一个 110ms 的小动画落回格子。
3. **每帧都在重组**：行高表原来是 `mutableStateMapOf`，拖动中每次布局都会触发重组；
   现在改成普通 `HashMap`，只给手势回调读。其余行换位时用 `animateItem()` 平滑滑动。

换位与「落位后反推一条 `move-project`（锚点 + before/after）」的算法抽成了纯 JVM 文件
`WorkspaceDragReorder`，并补了 `WorkspaceDragReorderTest`（阈值、行高不等时的补偿、
一次事件跨多行、置顶分组边界、锚点永不自指）。顺带：长按起拖时补一记触感反馈，
跟随「设置 → 触感反馈」开关。

### ✂️ 项目菜单去掉「排序 ↑↓」那一行

项目长按菜单（工作区弹窗）里那段「排序 上移/下移」箭头行已整行删掉——
既然列表可以直接长按拖动排序，菜单里再放一组箭头既是重复入口，也容易点错。

## [1.8.17] - 2026-11-01

### 🔧 修复 1.8.16 发版失败：`isMainFrame` 写错，CI 卡在单元测试编译

1.8.16 的 Tag 推送后，CI 在 `Run Unit Tests` 的 `:app:compileDebugKotlin` 阶段就红了，
**没有产出安装包**（`Build Release APK` 被 skip）：

```
e: KaTeXFormulaView.kt:232:26 Unresolved reference 'isMainFrame'.
```

`WebResourceRequest` 的方法是 `isForMainFrame()`，不是 `isMainFrame()`——
仓库里 `GenerativeUi.kt` 早就在用正确写法，是我新写这个 WebView 时凭印象写了错的名字。
本版修正该引用，并把 `view.tag` / `view.tag = …` 改成显式的 `getTag()` / `setTag(…)`，
避开 `View.setTag(Int, Any?)` 重载在合成属性上的歧义。

**教训已写进准则**：AGENTS.md §1.1 明令禁止本地编译（含 `kotlinc`、Gradle 任务、用 stub 凑类型
的“单独编一下”）。这次之所以能漏掉一个大小写级别的 API 名错误，正是因为此前用 stub 通过了编译，
产生了假的“已验证”信号；从本版起，编译与测试只看 GitHub Actions 的结论。

1.8.16 的功能内容不变，随本版一并交付（KaTeX 真渲染 + LaTeX 命令名泄漏修复）。

## [1.8.16] - 2026-11-01

### ✨ 公式真渲染：内置 KaTeX，块级公式与 pi-web 视觉对齐

用户截图里那块公式卡片显示的是**原始 LaTeX**（`\text{总放线长度 } L = \Delta X…`），
行内箭头则被印成了 `rightarrow`。查下去是两处独立的实现缺陷，外加一层架构缺口：

**缺陷 1：块级公式卡片根本没进分词器。**
`MathFormulaBlockCard` 把 ```math 里的裸 LaTeX 传给 `LatexMathParser.appendTextWithMath`，
而后者只对 `$…$` 片段分词 —— 没有 `$` 的整段 LaTeX 走逐字符直出，于是反斜杠原样上屏。
新增 `LatexMathParser.appendFormula`（直接分词）作为原生通道入口。

**缺陷 2：没登记的 LaTeX 命令会把命令名当正文吐出来。**
`parseCommand` 的 `else -> MathToken.Text(cmd)` 就是那句「基站 rightarrow 机柜」的来历。
现在命令解析统一走新增的 `LatexSymbols` 符号表（箭头/希腊字母/大型运算符/关系符/括号/装饰符
共 150+ 条），认不出来的命令一律**丢弃**而不是输出命令名；`\begin{aligned}`、`\left(`、
`\vec{}`、`&`、`\\` 这些结构命令也有了明确处理。

**架构缺口：我们从来没有真正的排版引擎。**
上游 pi-web 用的是 `katex ^0.16.47` + `remark-math`，而 piDroid 只有一个「LaTeX → Unicode 近似」
的降级渲染器（分式只能写成 `(a) / (b)`，矩阵/多行对齐无从谈起）。本版一步补齐：

- `app/src/main/assets/libs/katex/` 内置 KaTeX 0.16.47：`katex.min.js`、`katex.min.css`、
  20 个 `woff2` 字体（只留 woff2，APK 净增 ~600 KB，无网络依赖）；
- 新增 `KaTeXFormulaView`：一个 `AndroidView` + WebView，页面里的
  `https://pi-katex.local/…` 由 `shouldInterceptRequest` 全部映射到 assets，
  **不开 JS 桥**、禁文件访问、禁一切外链导航；
- 高度由页面内经 `getBoundingClientRect` 实测后回报（字体异步落盘后再校准一次），
  公式比屏幕宽时按比例 `transform: scale` 缩放而不是被裁掉；
- KaTeX 缺失/脚本报错/加载失败一律回退到原生近似版（`LatexMathParser.appendFormula`），
  不留白板；公式文本稳定 140ms 才排版，避免流式输出把 WebView 重载打满；
- 公式卡片保留「∑ 数学公式 + 复制」的原有外壳，复制内容仍是纯 LaTeX。

**边界说明**：行内 `$…$` 仍然走原生通道 —— 一张公式一个常驻 WebView 会吃掉长消息的显存，
而这一版的符号表已经把行内最刺眼的箭头/希腊字母/运算符全部补上。

### ✅ 验证

- 新增 `LatexSymbolsTest`（6 组）与 `KaTeXPageTemplateTest`（6 组）纯 JVM 单元测试，
  守住「未知命令不得漏成命令名」「公式不得从 `<script>` 里逃逸」「颜色必须走白名单」；
- 这两组测试刻意只依赖纯 Kotlin（不碰 `android.*` 与 Compose 类型），所以不需要也不允许
  “先在本地编一遍”——编译、单元测试、打包、签名全部由 GitHub Actions 完成（AGENTS.md §1.1）；
- CI 若报红，用递增的补丁版本重新走一遍发版 SOP，不复用同一个 Tag。

## [1.8.15] - 2026-11-01

### 🔧 修复 1.8.13/1.8.14 发版失败：单元测试引用了已删除的常量

这两版都卡在 CI 的 `Run Unit Tests`，**没有产出安装包**。根因很直接：

1.8.13 移除了「助手消息只留 400 字」的特殊裁剪，删掉了常量
`PromptEnhancementRules.MAX_CONTEXT_ASSISTANT_CHARS`，但对应的单元测试还在引用它 ——
`unresolved reference` 让整个测试源集编译失败。我此前只跑了服务端测试（本机无 Android SDK），
漏掉了 Kotlin 侧的这一处。

本版修复：

- 删掉引用已删除常量的旧测试，换成两条正向断言：「长助手回复不再被砍到 400 字」
  「工具结果与压缩摘要能进入上下文」；
- 补一条预算一致性测试（12 条 / 1200 字 / 12000 字），防止客户端与服务端口径再次漂移；
- 修正过时的裁剪断言（单条上限已从 2500 改为 1200）；
- 复查 `selectContext` 新签名与全部调用点（主源码 1 处 + 测试 6 处），以及
  `buildPromptEnhancementRules` 引用的符号是否都存在。

1.8.13 / 1.8.14 的功能内容不变，随本版一并交付：

- 工具结果（toolResult）进入增强上下文；
- 完整历史（含翻页加载的更早记录）；
- pi 压缩摘要以 summary 角色注入；
- 规则 pe-4：事实优先、篇幅分档、禁止反引号与 `**`；
- 超时按实测（P50 4.1s / P90 7.7s）定为服务端 30s、客户端 35s。

---

## [1.8.14] - 2026-11-01

### ✨ 提示词增强补齐三项：规则 pe-4、压缩摘要、超时定值

**规则升级到 pe-4**（纯提示词，无架构改动）：

- `【待确认】` 从「默认」改为「兜底」：材料里写明的事实——文件名、符号名、行号、错误类型、
  技术栈、早期约定——必须照实写进结果，不许含糊成「相关文件」「某个报错」；
- 篇幅按复杂度分档：简单修正 120~250 字、常规 250~450 字、复杂任务可到 700 字（取消 420 字硬顶）；
- 格式约束收紧：禁止任何反引号（含行内）与 `**`，结构标题只用【】。

**注入 pi 的压缩摘要**（替代「网关读 jsonl」）：

长会话被 pi 压缩后，早期约定只剩一段 `compactionSummary`。它本来就是会话里的一条消息，
现在清洗掉「📦 上下文已归档压缩」这类界面文案后以 `summary` 角色注入上下文。
这解决的是「一开始定的方案对增强模型等于不存在」——而读盘上的 jsonl 还要额外承担
路径编码、`parentId` 分支重建、并发写半行三种风险，收益相同。

**超时按实测定值**：

5 个样本实测 P50 4.1s / P90 7.7s（上下文含工具结果与摘要）→ 服务端 25s→30s、客户端 30s→35s。
既给长尾留机会，又不至于让用户等到一分钟还以为卡死。

### ✅ 实测（同一句「这个怎么修」）

上下文只有一条压缩摘要（Kotlin + Compose、最小侵入、不引入新依赖）加一条 grep 工具结果：

```
【目标】定位并修复 ChatScreen.kt 中 enhancementEnabled 分支相关的缺陷。
【已知上下文】项目是 Kotlin + Jetpack Compose 的 Android 客户端。grep 结果显示，
              enhancementEnabled 只在 ChatScreen.kt:1841 一处出现……
【待确认】当前没有提供具体故障现象。请补充：触发路径、实际表现与预期表现、报错堆栈……
```

5.4 秒返回 657 字，无反引号、无 `**`，【待确认】仅 1 处且是真的缺信息。
pe-3 时期同样输入看不到技术栈与早期约定，【待确认】平均 2~3 处。

服务端 86 项测试通过（新增：compaction 摘要进上下文）。

### 🧭 明确不做：网关按 sessionId 读 jsonl

客户端已有 `olderMessages + messages` 与压缩摘要；读 jsonl 需要处理路径编码耦合、
`parentId` 树重建、追加写半行容错、路径白名单。触发条件是：后续评测里真的出现
「因缺少早期上下文而答偏」的样本。

---

## [1.8.13] - 2026-11-01

### ✨ 提示词增强终于拿到了它真正需要的信息：工具执行结果

「效果像个 demo」的根因不是提示词写得不好，而是**喂给模型的信息缺了一半以上**。
实测一个真实会话（230 条消息）：

```
role 分布：assistant 109 | toolResult 119 | user 1 | system 1
```

而旧实现把 `toolResult` 全过滤掉了。用户说「这个报错」「刚才改的那段」——信息就在里面。

本次只改三处，没有新增任何架构：

- **纳入工具结果**：`MessageRole.TOOL` 不再被丢弃，取 `toolName` + 命令 + 输出
  （无输出时退回预览），以错误结束的会额外标注；
- **用完整历史**：`olderMessages + messages`，翻页加载的更早记录也能进上下文；
- **不再砍助手消息**：删掉「助手只留 400 字」的特殊裁剪（那会把上一轮的方案截断），
  上下文预算 8→12 条、6000→12000 字。

刻意没做：不读 `~/.pi/agent/sessions/*.jsonl`（客户端已有数据）、不做 token 估算、
不做相关性排序、不改提示词规则——先把信息喂够再谈规则。

### ✅ 端到端验证（5.7 秒）

上下文含一条编译错误工具结果（`./gradlew assembleDebug` → `unresolved reference:
enhancementEnabled at ChatScreen.kt:1841`），草稿「这个报错怎么修，先别改代码」，
输出同时命中 `enhancementEnabled`、`ChatScreen.kt`、`unresolved reference`、`1841`，
并保留了「只读检索、不要输出补丁」的约束。

服务端 85 项测试通过（新增：工具结果进上下文、长助手回复不被砍）。

---

## [1.8.12] - 2026-11-01

### 🐛 修复同一目录在工作区列表里分成两行（`D:\` 与 `D:/` 写法不一致）

现象：左侧「工作区项目」里 `termius` 出现两次 —— 一行 `D:/myscript/termius`（有会话），
一行 `D:\myscript\termius`（0 个会话），同一个目录被当成两个项目、会话只算到其中一行。

两条链路写入的写法本来就不同，本版不再「挑一边改掉」，而是把两者归到同一个身份键：

- App 侧：`/api/cwd/browse` 的盘符目录返回 `D:\`（平台分隔符），手机端点选后**原样落库**成 `D:\myscript\termius`；
- 会话/pi-web 侧：`projectRoot` 来自 `git rev-parse --show-toplevel`，是 `D:/myscript/termius`。

对齐方式以 pi-web 的实现为基准 —— 它本身就把「分组标识」(`projectKey`：分隔符归一、去尾部斜杠、
win32 上忽略大小写) 与「展示路径」(`projectRoot`：保留原始写法) 分成两层。本版照此实现：

- 新增 `WorkspacePaths` 归一化层与 `groupWorkspaces()`，分组按身份键，展示仍沿用会话自己那份写法；
- 自定义目录**写入即归一**为正斜杠写法、读取同样过一层（旧的反斜杠写法照常显示并合并，不会被改坏）；
- 置顶、项目别名、选中态、归档分组、按项目筛会话、FAB 新建会话的 cwd 全部改为身份键比较，
  历史遗留的反斜杠置顶/别名仍能认回来。

### ✅ 验证

- 新增纯 JVM 单测 `WorkspacePathsTest`（截图场景、盘符大小写、尾部斜杠、UNC、POSIX 大小写、盘符根）；
- 用 `Models.kt` + `WorkspaceGrouping.kt` 真实源码在 JVM 上跑通 8 组场景：截图场景合并为一行、
  同一目录的两种历史写法只出一行、不同项目仍分离、Linux 上大小写不同目录不合并。

---

## [1.8.11] - 2026-11-01

### 🐛 修复提示词增强「莫名报 499」

499 是网关自己给出的「客户端先断开」码，与模型无关。查到两个真实来源，本版都修掉：

1. **能力探测抖动误伤在飞请求（主因）**
   客户端每次 `loadCapabilitiesAndModels()` 都会重新探测网关能力，而旧代码把
   「这次没探测到」直接写成 `null`。一次 ping 超时就足以让增强目标变成 null，
   进而触发 `LaunchedEffect` **取消正在进行的增强请求** —— 服务端看到连接断开，
   回 499。用户只是点了魔棒，却收到一个看不懂的状态码。
   - 能力探测改为三态：成功 / 明确不支持 / 本次没连上；只有前两者改变入口可见性，
     没连上时保留上一次结果；
   - 连接与模型身份「粘住」最后一次有效值，探测抖动既不会取消请求，也不会让回填
     因 `modelKey` 变成空串而被 `canApply` 拒绝。

2. **超时只发 abort 信号、不发返回**
   某些 provider 不检查 signal（卡在 DNS / TCP 握手 / 上游排队时尤其常见），
   只发 abort 会让 `await` 一直挂着；服务端迟迟不回，客户端 30s 先超时，
   于是又一次表现为 499。现在服务端在 `timeoutMs + 500ms` 自行强制返回
   `TIMEOUT`(504)，不再依赖 provider 配合。

### 🔧 顺带把「取消」从失败里摘出去

- 网关返回 499 时客户端映射为 `CANCELLED` 并**静默**处理（切会话、点取消本就不该弹失败）；
- 本机网络超时（`SocketTimeoutException` / `InterruptedIOException`）提示「增强超时，已保留原稿」，
  不再是含混的「增强失败」。

### ✅ 验证

- 服务端 84 项自动化测试全通过（新增 2 项：provider 忽略 abort 时仍能按时超时、
  客户端离开时归为 CANCELLED 而非 TIMEOUT）。
- 实测：正常增强 7.4s 返回 200；客户端中途断开后**同一会话立即重试 3.8s 成功**，
  证明断开后并发名额已正确释放、不会卡在 499。

---

## [1.8.10] - 2026-11-01

### 💬 全部 Toast 文案重写：去掉模板腔，修掉 4 条说反话的提示

175 处 `Toast.makeText` 逐条过了一遍，改写 111 条：

- **删掉自我加冕**：`已成功绑定设备` / `已成功分叉出新会话！` / `已成功接入 X！` 里的「成功」和
  感叹号，只留「已…」；`上下文压缩完成！` → `上下文已压缩`。
- **删掉装饰与形容词**：两处 `✨`；`正在智能压缩上下文` → `正在压缩上下文…`；
  `已批量开启 N 项技能` → `已开启 N 项技能`；`所有技能均为最新版本` → `技能都已是最新版本`。
- **标点统一全角**：`失败: msg` → `失败：msg`，`相册 (path)` → `相册（path）`，
  `安装中...` → `安装中…`。全仓 Toast 不再混用半角冒号、半角括号和英文省略号。
- **绕口句改写**：`网关未生效，X 仍保留在 PC 上` → `未同步到网关，X 仍留在 PC 上`；
  `已清除 X 的已保存 Key` → `已清除 X 保存的 Key`；`读取图片异常` → `读取图片失败`；
  `无法打开的链接：…` → `打不开这个链接：…`。

顺手修掉 4 处文案与行为不符：

- **任务完成通知的开关播报错了对象**：`taskCompletedNotifyEnabled` 只控制「任务完成发通知」，
  Toast 却念「已开启常驻通知栏保活守护 / 已关闭常驻通知栏（仅在任务运行时启动前台服务）」。
  改为「任务完成时会发通知 / 任务完成后不再通知」。
- **失败分支说成功，还把内部动作码漏给用户**：插件与 MCP 的 `onFailure` 原提示
  「插件状态已更新 (enable)」——既说成了成功，又把 `enable` / `remove` 这类内部动作码直接
  显示出来。改为「插件状态已存本地，同步到 PC 失败：X」，与既有的
  「改名已存本地，同步到 PC 失败」统一。
- **404 文案是半句话**：`GatewayError` 的「请确认 pi-web 与 App 版本」补成
  「可能是 pi-web 与 App 版本不匹配」。

没动的：隧道断开、密钥失效、任务执行中拦截、发送失败退回输入框这些
「说清发生了什么 + 下一步做什么」的提示只统一了标点，措辞保持原样。

**验证**：仅改字符串字面量，`${...}` 插值里的代码原样保留；7 个改动文件引号与大括号计数
前后一致；脚本复查 175 个 Toast，半角标点、「已成功」、`✨` 残留为 0。本地未编译，
按规范留给 CI。

## [1.8.9] - 2026-11-01

### ✨ codywon 全系模型开启 `xhigh`（已用真实请求验证可用）

1.8.7 恢复了 `max`，`xhigh` 因为我担心网关不认而留着。按你的要求开启，并且**先验证再交付**：

- 给 codywon 的 7 个模型的 `thinkingLevelMap` 补上 `"xhigh": "xhigh"`；
- `/api/models` 复核：7 个模型档位均为
  `[off, minimal, low, medium, high, xhigh, max]`；
- **真实跑了一次**：临时会话 → `set_model` codywon/deepseek-flash → `set_thinking_level xhigh`
  → 发一条极短提示，`stopReason = stop`、无 `errorMessage`，正文正常返回，
  说明你的 codywon 网关接受 `xhigh` 这个 `reasoning_effort` 取值（会话已删除）。
- 备份：改前另存 `models.json.bak-xhigh-20261101-042840`。

`deepseek` 内置提供商（`deepseek-flash` / `deepseek-v4-pro`）**未改动**：
它们的档位来自 pi 官方注册表（`off/low/high/max`），要开 `xhigh` 需要额外在
`modelOverrides` 里写 map，且未经实测，需要的话说一声我再单独验证后开启。

## [1.8.8] - 2026-11-01

### 🔒 安全：伴侣网关的文件接口不再能越界读写

Token 等价于本机权限，但拿到 Token 之后也不该能把网关当「任意文件读写器」：

- `GET/PUT /api/skills/content`：路径先做 `realpath`（解析符号链接与 `..`），必须落在技能库
  （`~/.pi/agent/skills`、`<cwd>/.pi/skills`、`.claude/skills`）内，或带 `skills` 目录段的
  技能包路径；且必须是 `.md`。技能名只接受单段，`../` 直接丢弃。以前只查 `.md` 后缀，
  `~/任意.md` 可以被读、也可以被覆盖。
- `GET /api/files/raw`：必须是会话工作目录（无 cwd 时退回主目录）内、类型白名单里的
  真实文件，越界返回 403。客户端对 403 会回落到 pi-web 的 `/api/files?path=`（那条自带
  `isFilePathReferencedBySession` 校验），正常图片加载不断。
- `POST /api/cwd/browse`：只接受绝对路径 / `~` 开头，子目录名必须是单段 —— 以前相对路径
  会被解析到网关自己的目录，`..\` 可以把目录建到别处。

新增 `server/test/gateway-paths.test.mjs`（15 项，含 HTTP 层）：`npm test` 共 **76 通过 / 0 失败**。

### 🐛 修复

- **扫码页不再漏线程**：分析线程池、MLKit 解码器、相机绑定都挂在 `DisposableEffect` 上释放；
  异步相机初始化在页面已关闭后不会再绑定。相册识别用的解码器任务结束即关闭。
- **PDF 缩略图不再留垃圾**：以前固定文件名且从不清理，浏览过多少 PDF 就在 `cacheDir` 里
  攒下多少份副本；现在用唯一临时文件并在 `finally` 删除。
- **任务完成提示音**：播放被中断或 `write` 抛异常时也会 `stop()+release()`，不再漏 `AudioTrack`。
- **语音输入**：`onEndOfSpeech` 的 1 秒延迟提交以前是匿名任务，取消后 1 秒内重开识别会把
  **新一轮**的部分文字当成上一轮结果提交；现在保存引用 + 识别轮次双重校验。
- **会话快照**：「查时间戳 → 写 `.tmp` → rename → 记时间戳」按会话串行化（16 把固定锁），
  同一会话不会再并发写同一个临时文件；写失败不再被记成「刚写过」，离线回放不会一直空着。
- **用其他应用打开**：缓存文件名带「连接身份 + 完整路径」哈希，不同目录/不同网关下的同名
  文件不再互相覆盖。
- **Git 源码查看**：按 120 行分块进 `LazyColumn`，首帧不再一次性排版整份文件；块边界不会多出空行。
- **应用内更新**：已安装包的签名改用 `GET_SIGNING_CERTIFICATES` 读取，与解析 APK 的口径一致
  （密钥轮换/多签名者下才有正确结果），低版本才退回 `GET_SIGNATURES`。

### ⚙️ 网关

- 提示词增强的限流桶按授权头哈希分桶，Token 轮换会留下不回收的键；超过 256 个桶时清理整桶
  都已过窗的主体，长期运行不再慢速增长。

## [1.8.7] - 2026-11-01

### 🐛 codywon 的模型推理档位最高只能到 high（P0 的二次损失，已补回）

现象：无论 pi-web 还是 App，`codywon/deepseek-flash` 等模型的思考档位最高只能选到 `high`，
`max` 消失。

**规则（pi-ai `getSupportedThinkingLevels`，实测源码）**

```js
EXTENDED = ["off","minimal","low","medium","high","xhigh","max"]
reasoning ? EXTENDED.filter(l => {
   const mapped = model.thinkingLevelMap?.[l];
   if (mapped === null) return false;
   return (l === "xhigh" || l === "max") ? mapped !== undefined : true;   // ← 关键
}) : ["off"]
```

`xhigh` / `max` **只有在该模型的 `thinkingLevelMap` 里显式写出来才存在**，
没写就默认只到 `high`（`off…high` 无需声明）。

**为什么会丢**：P0 那次把 `providers.codywon.models` 整个数组删掉时，
每个模型的 `thinkingLevelMap`（连同 `name`/`input`）一起被删了；
残留的 `modelOverrides` 只带 `contextWindow`/`maxTokens`，所以恢复模型定义时补不回这张表。

**已补回**：给 codywon 的 7 个模型各加了

```json
"thinkingLevelMap": { "low": "low", "medium": "medium", "high": "high", "max": "max" }
```

`/api/models` 复核：7 个模型的可选档位已从 `[off…high]` 变回
`[off, minimal, low, medium, high, max]`；deepseek 内置的 2 个不受影响。

> `xhigh` 仍未开启：它不是 OpenAI 兼容接口的标准 `reasoning_effort` 取值，
> 冒然映射有被网关 400 的风险。需要的话在模型编辑里给它加一条
> `"xhigh": "xhigh"`（或告诉我，我改）。

**工程**：新增回归用例 `custom model thinking level map survives a save round trip`
（自定义模型带 `thinkingLevelMap` 经 combine → serialize 往返后不丢），
`ProviderConfigMergeTest` 11 项本地全绿。

## [1.8.6] - 2026-11-01

### 🔧 修 CI：`PromptEnhancementRulesTest` 断言参数顺序写反，导致 1.8.4 / 1.8.5 都没出包

1.8.4 与 1.8.5 的 tag 流水线都停在「Run Unit Tests」：

```
PromptEnhancementRulesTest.kt:149: argument type mismatch: 'kotlin.Boolean' but 'kotlin.String?' expected
```

JUnit 4 的多参签名是 `assertTrue(String message, boolean condition)`，
而测试里写成了 `assertTrue(condition, message)` —— **编译期就挂了**，APK 自然没构建。
改为正确的参数顺序（两处），语义不变。

> ⚠️ 因此：**1.8.4 的 P0 修复（模型管理保存时不再清空 codywon 的 models 数组）从本版才开始真正生效**。
> 之前若升级到 1.8.4/1.8.5 失败，属正常 —— 那两个版本没有产物。

另外：`server/.diag-*.mjs` 这类本地诊断脚本加入 `.gitignore` 并取消跟踪（文件仍留在磁盘）。

## [1.8.5] - 2026-11-01

### 🐛 修复提示词增强「转圈很久、内容不变」（真实根因三连）

用户反馈点魔棒后长时间转圈、最后正文毫无变化。逐层定位到三个真实缺陷，本版全部修掉：

1. **上游拒绝 `developer` 消息角色（主因）**
   pi 的 `openai-completions` 适配器按新版 OpenAI 规范把 system 提示构造成
   `role: "developer"` 消息，而 models.json 里声明的第三方中转端点只认 `system`，
   直接返回 `422 unknown variant developer`。旧代码把这个 4xx 吞成「模型请求失败，
   请稍后重试」，于是既转圈又改不了内容。
   现在对「models.json 声明了 baseUrl 的自定义 provider」统一发送
   `supportsDeveloperRole: false` / `supportsStore: false`，目录解析出的模型也一并归一化。

2. **模型解析被判「不支持」**
   pi 1.4 起 models.json 用 `modelOverrides` 声明自定义 provider，模型目录来自
   `models-store.json`；当该 provider 目录缓存为空时 `getModel()` 返回 undefined，
   增强请求被误判成 `UNSUPPORTED_MODEL`。
   现在目录未命中时按 models.json 的声明就地合成可用模型；真的无法解析时，
   错误提示会点名具体缺少什么（不再是笼统的「不支持」）。

3. **首次调用卡在网络模型目录刷新**
   `ModelRuntime.create()` 默认会联网刷新模型目录，实测首次卡 14.5 秒。
   现在关闭联网刷新（用盘上目录，35ms），并在网关启动后异步预热。

### ⚡ 同时把效果对齐 WorkBuddy 的写法

- **提示词策略升级到 `pe-3`**：按「目标 / 输入 / 动作 / 约束 / 输出 / 验收」组织，
  按需取用不硬凑；此前 `pe-2` 只加句号的「假增强」不再出现。
- **格式适配手机输入框**：禁止 Markdown 加粗与井号标题，结构标题一律用中文方括号
  `【】`（纯文本渲染下不会被当成字面量符号）；篇幅 180~420 字。
- **上下文预算重新标定**：总 6000 字 / 单条 1200 字 / 最多 8 条；助手长回答只取开头
  400 字，把预算留给用户写下的「先给建议别改代码」这类约束（与 pi-web 生成会话
  标题时的 800/300 分档做法一致）。
- **超时收紧**：服务端 25s（实测 4~7s），客户端 30s，失败就立刻提示而不是让用户干等。
- **增强成功/撤销即时反馈**：Toast「✨ 提示词已增强」「已恢复原始输入」。

### ✅ 验证

- 服务端 61 项自动化测试全通过（新增 9 项：developer role、模型合成、上游 4xx 透出、
  占位符在多个小节重复出现、助手回答分档裁剪）。
- 端到端实测（当前实际模型 codywon/deepseek-flash）：**4.9 秒**返回，
  `@ChatScreen.kt` 出现两次均逐字还原、无残留占位符，`先给建议别改代码` 约束完整保留。
- 修复了一处真 bug：模型把同一引用写在多个小节时，旧恢复逻辑只替换首次出现，
  会误判 `INVALID_OUTPUT`（这条同样表现为「转了半天内容没变」）。

---

## [1.8.4] - 2026-11-01

### 🚨 P0：模型管理里改 deepseek 参数，把 codywon 整家的模型清空了

**现场**：在 App 的「模型管理」里改 deepseek 的上下文窗口/最大输出并保存后，
`~/.pi/agent/models.json` 里 `providers.codywon.models` **整个数组消失**，
`/api/models` 只剩 deepseek 的 2 个内置模型，codywon 的 7 个模型在模型面板里全部不见
（`baseUrl` / `api` / `apiKey` 还在，API Key 也在 `auth.json`，所以只是「模型定义」被抹掉）。

**根因（不是保存时算错了模型，而是供应商被认成了内置）**

- `combineProviders()` 把「凭据库里有 key 的 provider」一律当成内置 provider。
  `codywon` 是 models.json 里的自定义端点（有自己的 `baseUrl` + `models` 数组），
  但它在 `auth.json` 里也存了 key，于是 `apiKeyProviders` 里 `configured=true` → 被判成内置；
  `pureCustom` 又把真正的自定义条目过滤掉，界面上 `codywon` 只剩「内置」这一份。
- 保存时 `serializeProvidersConfig()` 走内置分支：`existingP.remove("models")` + 只写
  `modelOverrides`（只带 contextWindow/maxTokens，名字/vision 一并丢失）。
  pi 的模型注册表里没有这些自定义模型，`models` 一删就彻底解析不到了。

**修法**

1. **`combineProviders`**：models.json 里自己声明了 `models` 数组的 provider 一律按自定义处理，
   不再因为「auth 里有 key」被判成内置；`pureCustom` 也改成按「是否真的合并成内置」过滤。
2. **`serializeProvidersConfig`（防御）**：内置分支**绝不删除已存在的 `models` 数组**
   （只有 `baseUrl` 为空的内置 provider 才允许清，这正是 `sanitizeAndHealModelsConfig` 管的那种坏结构）。
3. **每次写 models.json 前自动备份**：落到 `cacheDir/models-config-backups/models-<时间>.json`，
   保留最近 10 份。这类整文件覆写一旦算错就是不可逆的，先留证据再动手。

**已经帮你恢复的数据**（本机 `~/.pi/agent/models.json`，恢复前已另存 `models.json.bak-p0-*`）

- codywon：`baseUrl` / `api` / `apiKey` 原样，`models` 数组按 7 个模型重建
  （`deepseek-flash`、`gpt-6-sol`、`gpt-6.1-sol`、`gpt-6-luna`、`qwen3.8-flash`、
  `claude-opus-4-6-thinking`、`gemini-3.8-flash-high`）。
- `contextWindow` / `maxTokens` 取自残留的 `modelOverrides`（没丢）；`name` 取自事故前的
  `/api/models`；`reasoning` 与 `input: [text, image]` 按事故前模型列表里的图标恢复
  —— **这两项是重建值，若某个模型其实不支持图片/思考，在模型管理里关掉即可**。
- deepseek 的 `modelOverrides`（你刚编辑的 1048576 / 393216）原样保留。
- 复核：`/api/models` 已回到 9 个模型，默认模型仍是 `codywon/deepseek-flash`。

**工程**

- 新增 3 条 P0 回归用例（`ProviderConfigMergeTest`）：声明了 models 的自定义 provider 不被判内置、
  编辑内置 provider 后保存不丢自定义模型、误判为内置时也不许删 `models`；
  另用真实 `models.json` 跑了一遍 combine → serialize 往返（7 个模型、baseUrl、apiKey 全部保留）。

## [1.8.3] - 2026-11-01

### 🐛 修「会话模型在 gemini-3.8 与 deepseek-flash 之间来回横跳」（1.7.62 探针的回归）

现象：PC 上这条会话是 `gemini-3.8-flash-high`，App 打开后先显示 `deepseek-flash`，
过一会变成 `gemini-3.8-flash-high`，随即又跳回 `deepseek-flash`，如此往复。

**根因是我在 1.7.62 加的 3s 探针用错了数据源。** 拿正在跑的网关对这条会话（`01a18ba4`）实测：

```
GET /api/sessions/{id}/context?tail=1    2.7 KB   model = deepseek/deepseek-flash        ← 分支记录（错）
GET /api/agent/{id}?systemPrompt=0       1.2 KB   model = codywon/gemini-3.8-flash-high  ← 运行时（对）
文件里最后一条 model_change（#1296）       codywon/gemini-3.8-flash-high（最近 435 条 assistant 全是 gemini）
```

pi-web 的 `context.model` 取的是**当前 leaf 分支上最后一个 `model_change`**（chunk 5582 的
`W(entries, leafId)`），而分支上的 `model_change` 可能不在 leaf 上 —— 于是它与运行时不一致。
探针 3s 一次把它写进界面，全量快照 10–15s 一次把运行时写回，两者打架 = 横跳。

顺带发现第二个坑：`/context` 的回包是**整段 LLM 上下文投影**，不受 `tail=1` 约束，
同一会话实测 **2.7 KB → 122 KB**（`tail=60` 到 6.3 MB）。放进 3s 循环本身就是灾难。

**修法**

- 探针改用 **运行时状态** `GET /api/agent/{id}?systemPrompt=0`（实测 **1.2 KB**，就是 App 已经在用的
  `getAgentState`），只认运行时模型与思考档；运行时未存活直接跳过（此时不存在权威模型，
  交给全量快照的分支记录，与 pi-web 前端行为一致）。
- 删除 `SessionModelRef` / `SessionModelRefParser` / `PiApiClient.getSessionModelRef`
  （不可信且体积无界的那条路彻底移除）。
- 模型面板打开时同样改用运行时状态对账（1.2 KB），不再用 `/context`。

**记录**：`docs/DESIGN-model-sync.md` 补 §2.1「`context.model` 不可信、体积不定」的实测与结论；
另外验证了 `set_model` 会让 `/api/agent/running` 的 `sessionListVersion` 自增（40 → 41 → 42），
所以「版本变化 → 立刻全量快照」这条既有通路本身也能感知 PC 端切模型。

## [1.8.2] - 2026-11-01

### 🖼️ 图片「进视口才加载」+ 长回合过程组不再一次性铺满

上一条（1.8.1）把图片字节落盘与翻页 payload 收下来，这条把「到底什么时候发请求」也收住。

- **图片 / PDF 缩略图改为「进过视口才请求」**：一个回合的过程组在列表里是**单个 item**，展开时
  组内几十张卡片会在同一帧全部组合出来，光靠 LazyColumn 的可见性拦不住。现在卡片自己看坐标
  （`LocalMediaViewport`，上下各预留约 0.6 屏做预取），没进屏只画一行文件名占位，不转圈、不发请求。
  进过一次就不再回退，滚动来回不会反复请求/重复解码。
- **同时在飞的图片请求上限收到 3**（OkHttp 默认每 host 5）：网关大多经 Cloudflare 隧道，
  十几张一起拉只会互相拖慢，连正文和事件流都排到后面。
- **长回合过程组只渲染最近 40 条**，更早的用一行「显示更早的 N 条过程」按 40 条递增展开
  （`ProcessWindowPolicy`）。这一步压的是**组合量**本身 —— 图片已不拉，但上百次工具调用的回合
  一次性组合上百张卡片仍然要卡几百毫秒。常规回合（≤ 40 条）完全不变。

> 为什么不做「组内换成嵌套 LazyColumn」：纵向列表套纵向列表会抢手势，而且窗口化会改变行 key
> 与滚动锚定 —— 本项目此前那套「按条数切窗口」正是因为各种空白与停半路被回滚过。

## [1.8.1] - 2026-11-01

### 🖼️ 图片多的会话不再「一张张转圈」：图字节落盘缓存 + 降采样解码

面向「一个会话几十张 CAD 出图」的场景。实测样本：1263 条消息、58 条带图、工具结果图
base64 合计 15.5MB；最近 60 条消息的 JSON 就有 6.45MB，其中 6.36MB 是图片。

- **往上翻页漏了 `deferMedia=1`（最大的一笔）**：`/api/sessions/{id}/context` 的翻页请求
  之前没带这个参数，于是每翻一页就把该页工具结果图的 base64 全量拉回来（单页可达数 MB）。
  现已补上（含内部全量补页通道），与首屏快照保持一致；pi-web 会把这些图改写成
  `…/tool-result-image` 的 URL，图再按需取。
- **图片字节新增磁盘缓存**：落在 `cacheDir/media-bytes`（池上限 64MB，按最后访问时间淘汰），
  冷启动/进程被回收后再打开会话直接命中，不必重下原图。缓存 key 按「内容位置」算，
  且工具附件带 `toolCallId` 版本号 —— 同一段历史无论重开多少次都命中，工具再被调用一次
  （同名图被重写）就自动失效，不需要用 TTL 去猜文件有没有变。
- **内存池改为按字节计容量**：旧实现 `LruCache(32)` 按项数计、单张放宽到 15MB，最坏≈480MB；
  现为 24MB 总预算，单张比池还大的图不再入池。
- **解码按屏幕降采样**：`BitmapFactory` 先读尺寸再按 2 的幂取 `inSampleSize`（目标长边 = 屏幕宽 × 2），
  4000×3000 的图内存占用从≈48MB 降到≈12MB，而卡片最大只显示 260dp。
- **修好 pi-web 的延迟图 URL**：`source.url` 形态（`deferMedia=1` 的产物）之前只读顶层
  `url`/`image_url.url`，整条附件被丢弃，图片只能靠 `read` 工具入参里的文件路径兜底 ——
  bash 产出的图永远不显示。现在解析与取字节都支持它，并作为路径取不到时的兜底通道。
- 顺手收口：`extractMediaFromContent` 在 `PiApiClient` 与 `PiSseClient` 各存一份逐字节相同的副本，
  已合并到 `MediaAttachmentParsing`，任何一侧的修复不会再漏同步。

## [1.8.0] - 2026-11-01

### ⚠️ 升级前必读：本轮更换了签名密钥，需要「卸载旧版 → 重新安装」

- **为什么换**：仓库历史审查发现，旧 release 签名密钥（私钥）与其密码曾进入**公开**仓库，
  且与 1.7.x 正式包的签名完全一致 —— 任何人都能用它签出「系统认为是 piDroid」的包。
  因此旧密钥视为泄露、永久作废，本轮整体轮换。
- **覆盖升级会失败，这是预期**：Android 只认签名指纹，1.7.x 无法直接升级到 1.8.0。
  请先**卸载旧版**，再安装 1.8.0。
- **卸载会清空 App 本地数据**（会话缓存、界面偏好、配对信息），重装后需要**重新扫码配对**。
- **新签名指纹**（可用于核对安装包是否官方）：
  `88:9F:3E:92:79:80:11:E6:E5:47:E4:19:C0:C2:B4:F6:E9:D1:56:96:FC:3B:B5:57:76:A6:33:ED:E7:FD:AB:61`
  旧指纹 `07:B4:1E:74:…:0A:70:C7` 已列入 CI 黑名单。

### 🔐 供应链与仓库卫生加固

- CI 新增护栏：解码 keystore 后先核对证书指纹，命中已知泄露指纹即中断发版，
  避免误用旧密钥再次出包。
- 同一轮完成的其他处置：网关访问 Token 已轮换（旧 Token 立即失效）；
  历史提交中的密钥、日志已从全部提交与 tag 中移除。

## [1.7.79] - 2026-11-01

### 🩹 发送失败不再让消息凭空消失，界面也不再假报「空闲」

- **根因**：`onSendMessage` 把「任何 `AgentCommandException`」都当成「服务端明确拒收」，于是隧道
  切换那一秒 frp 用它自己的 HTML 404 页应答的那批请求，全被当成「消息没进去」：乐观气泡被撤掉、
  正文退回输入框，屏幕上只剩上一轮还在跑的「正在执行 ctx_execute…」——看起来就像「我的消息发了、
  任务在跑、但气泡不见了」。同一分支还顺手把 `isAgentBusy` / 阶段文案清成空闲，于是停止按钮与
  发送箭头来回跳。
- **失败判定对齐 pi-web**（新增 `PromptRecoveryPolicy`，纯 JVM + 单测）：只有 pi 自己的
  `{"code":"prompt_rejected","accepted":false}`（例如内核在运行中拒绝普通 prompt）与
  `reason:"cwd-missing"` 才算「确定没送达」；HTML 404 / 502 / 超时一律降级为「结果未知」，
  保留气泡并走 `settleUnconfirmedPrompt` 对账，绝不误撤、也绝不自动重发。
- **运行态归服务端所有**：被拒的提交不再修改 `isAgentBusy` / `agentPhaseLabel` / 唤醒锁。
- **探不到状态不再当「跑完了」**：`scheduleAgentCompletionSettle` 与 `settleUnconfirmedPrompt`
  在 `GET /api/agent/{id}` 失败时只补一次快照对账，把收尾判定交给下一轮成功探测（旧实现
  `?: false` 会在网络抖动时把正在跑的回合判成结束，骗出那个注定被内核拒掉的发送箭头）。
- **运行中禁止发裸 prompt**：`submitMessage(SEND)` 在忙碌时直接指向「引导 / 排队」（pi 内核会
  拒绝 `streamingBehavior` 缺省的 prompt；pi-web 在运行中也只给这两条路）。
- **新建任务不再继承旧会话的运行态**：`onNewSessionClick` 一并清空 `streamingMessageId` /
  `isAgentBusy` / `isAutoRetrying` / `isToolExecuting` / 阶段文案 / 排队列表，新会话头顶不会
  再盖着上一场的「正在执行 xxx」。

## [1.7.78] - 2026-11-01

### ✨ 提示词增强体验与 Prompt 深度全面升级（废除微修保守策略）

- **解除过度防御的 Prompt 负向约束**：
  - 根因：此前系统提示词过于苛刻（“长度与原文相近”、“严禁补齐步骤与验收规范”），导致模型在面对简短输入时几乎只在句末加句号或微调标点，产生了“显示了撤销但输入内容看起来毫无变化”的假死体验；
  - 现已重构升级为 `pe-2` 工业级增强策略：针对口语化短句，智能扩展技术栈建议、核心功能点、状态管理、异常边界处理与代码验收规范；
  - 严格保留用户原始的否定与审批限制（如“先给方案别改代码”绝对不越权执行）；
  - 篇幅自适应控制在 150 ~ 380 字之间，在手机端呈现为半屏左右结构清晰、一目了然的高质量结构化指令；
- **回填交互反馈完善**：
  - 增强回填成功后即时弹出轻量 Toast 提示「✨ 提示词已增强」；
  - 原文完全无需修改时提示「提示词已足够清晰，无需优化」；
  - 点击“撤销增强”后即时提示「已恢复原始输入」。

### 🩹 隧道重连不再冒充「404」，错误提示改成可执行的下一步

- **根因**：公网隧道（frp）在没有客户端注册时，会用**它自己的 404 页面**应答每一个路径。
  更换 frps 版本的那 1~2 秒里，手机在飞的 9 个请求全部拿到这个页面，
  App 弹的是「获取会话列表失败: 404」——看起来像地址写错或接口不存在，
  实际只是隧道还没连回来；同一条 SSE 被切断，状态栏只能一直显示「自动重连中」。
- **失败归类**（`GatewayErrorClassifier`）：非 2xx 现在带出「状态码 + 路径 + 响应正文片段」，
  从而区分 **隧道掉线**（frp 的 HTML 404、nginx 的 502~504）、
  **本机 pi-web 未启动**（网关 502 正文点名 pi-web）、**密钥被拒**（401/403）、
  **接口真不存在**（pi-web 的 JSON 404）、**超时**、**连不上**。
- **提示语改成动作**：隧道掉线 → 「公网隧道暂时断开，正在自动重连（稍等几秒）」；
  pi-web 未启动 → 「请确认它已启动」；401/403 → 重新扫码；JSON 404 → 检查 pi-web 与 App 版本。
- **toast 去抖**（`ErrorToastGate`）：同类错误 60 秒内只提示一次。重连时一轮会同时失败
  十几个请求，以前是连珠炮，现在是一句话，且重连完成后界面自己恢复。
- **不改状态机**：SSE 退避重连、运行态对账、列表刷新节奏全部照旧，只改「怎么说」。

### ✅ 验证

- 新增 `GatewayErrorClassifierTest`（12 项）与 `ErrorToastGateTest`（3 项）纯 JVM 单测：
  覆盖 frp HTML 404、pi-web JSON 404、网关 502、nginx 502、401/403、5xx、超时/连接失败、
  历史字符串形态、空异常与 toast 去抖窗口。
- 线上事故样本已成为用例：`frpNoRoutePage` 就是当时 frps 真实返回的那段 HTML。

## [1.7.76] - 2026-10-31

### 🐛 修复提示词增强因会话上下文超长导致拒收报错的问题

- **对标 Roo Code / Cline 最佳实践，废除硬性拒收反模式**：会话上下文仅作为消解代词（如“那个接口”、“这个报错”）的辅助背景，绝对不应因为历史消息太长而抛出 `CONTEXT_TOO_LARGE` 阻断用户主任务；
- **服务端弹性滑动窗口与自动平滑截断**：
  - 无论会话有多长、单条有多大，服务端自动进行最近 N 条的滑动裁剪（Rolling Window）；
  - 单条消息超出预算自动截断并保留省略号，总字符超预算自动舍弃更早消息，保证用户点击增强永远 100% 顺畅执行，不再报错；
- **大幅提升上下文容量预算**：
  - 单条上下文预算由 800 字符提升至 2,500 字符（足以容纳完整代码段、堆栈日志与分析说明）；
  - 总上下文预算由 4,000 字符提升至 12,000 字符（约 3,000~5,000 token），极大提升复杂上下文下的代词消解准确率；
  - 修复客户端 `clip` 逻辑中多计 1 个省略号字符导致的边界溢出；
- **网关与单测验证**：已更新服务端 33 项自动化测试并全部通过，实测单条 3000+ 字符超长日志会话 200 OK 顺畅增强。

### ⚙️ 自动压缩改成 `/auto-compact` 命令（对标 pi-web），设置页清掉三条伪选项

> 本批与上一条提示词增强一起发布：`/auto-compact` 由 v1.7.76 的安装包首发，
> 之前误标的 v1.7.75 从未单独发版（版本号被本批任务占用）。

- **删除「系统设置 → 会话行为」整组**（自动压缩上下文 / 自动重试）。这两项是 pi 内核 `settings.json` 的全局策略（`compaction.enabled` / `retry.enabled` 默认都是 `true`），不是手机端旋钮；`pi-web` 也从不把它们放进设置页，只提供一条 `/auto-compact` 命令；
- **新增内置斜杠命令 `/auto-compact`**：输入框敲 `/` 即可在面板看到（`source=builtin`，描述沿用 pi-web 的 zh-CN「切换自动上下文压缩（全局设置）」）。执行链与 pi-web 同构：`get_state` → 取反 → `set_auto_compaction` → 回提示「自动压缩已开启 / 已关闭」。三个细节：
  - 读状态走 `get_state` **RPC**（`POST /api/agent/{id}`，pi-web 同一条通道）。不用 `GET /api/agent/{id}?systemPrompt=0`：那条不启 wrapper，空闲会话读不到该字段，再把「读不到」当成默认开启，就会每次都往关的方向扫；
  - 读失败直接中止并回提示（字段缺失才按内核默认 `true` 兜底，即 pi-web 的 `?? true`）；
  - 失败时把草稿还回输入框（pi-web 只弹报错，正文就丢了）；
- **删除「CPU 唤醒锁与断线自愈 · 已就绪」**：这一行的值是硬编码常量，点不了也不会变。唤醒锁（任务期自动持锁）与 SSE 退避重连（1s→10s，最多 6 次）本来就没有用户选项，`KeepAliveManager` 与 `PiSseClient` 一行未改；
- **自动重试只删不加命令**：pi-web 连 `set_auto_retry` 都没接，内核仍按其默认值工作，字段保留解析以对齐 agent 状态协议。

---

## [1.7.74] - 2026-10-31

### 🔧 修复提示词上下文选择导致的编译失败（最后一道阻断）

- `buildPromptEnhancementContext` 里把 `asSequence()` 后再调 `takeLast()`：`takeLast` 是 `List/Iterable` 的扩展，`Sequence` 上没有，导致 `compileDebugKotlin` 直接失败。改为先裁剪再映射，并在注释里标明原因；
- 本版同样包含 1.7.73 里列出的全部能力（0.13.0 协议对齐、配置无损保存、会话续租、提交与队列恢复、断流诊断）。

---

## [1.7.73] - 2026-10-31

### 🔧 修复 1.7.72 构建失败 + 网关失联诊断（上一版功能随本版全量发布）

- **修复 CI 编译失败（阻断发版）**：`PiApiClient.kt` 在重构模型目录读取时多留了一个右括号，导致整个文件解析失败，连带 `GlobalModelsConfigTab` / `GlobalSettingsScreen` 出现上百条「unresolved reference」级联报错；另修复 `ChatScreen` 中局部函数声明顺序与一个不存在的图标引用。本版已通过全量源码语法检查与 24 个纯 JVM 单元测试；
- **修复「一直自动重连中」无诊断**：SSE 自带退避重连，但网关（4100）或隧道未启动时，界面会永远转圈而不说原因。现在断流持续 20 秒且仍未恢复时，会一次性提示「请确认 PC 端已运行 start-server-tunnel.bat，或重新扫码配对」，连通后自动复位（不重复打扰）；

随本版一并发布的能力（v1.7.72 未成功产出 APK）：

- **模型配置保存安全**：移除「保存失败后换 `{config: …}` 包装重试」的危险逻辑（在 pi-web 0.13.0 下会绕过服务端校验并写坏 models.json）；错误文案优先透出服务端精准原因；
- **models.json 无损往返**：未在移动端建模的官方字段（单模型 `baseUrl`、`compat.forceAdaptiveThinking`、`inputLimits.images.resize.maxBytes`、provider 级 `oauth`/`authHeader` 等）保存时完整保留，删除项正常清理；
- **状态轮询瘦身**：切到 `GET /api/agent/{id}?systemPrompt=0`，大 `AGENTS.md` 项目单次轮询体积大幅下降；本地运行态判定补齐 `isCompacting`；
- **提交确认与草稿保护**：支持压缩期间发送（`{queued:true}` 转队列）、服务端拒收时正文与附件自动退回输入框、网络歧义走对账不重发、`cwd-missing` 明确提示目录已不存在；
- **Stop / 撤回退回消息**：服务端退回的正文与图片自动回填输入框（不覆盖用户新输入）；
- **会话生命周期**：每 30 秒 `POST /api/agent/{id}/lease` 续租，避免选中会话被 10 分钟空闲回收；实时解析 `queue_update` 与异步 `prompt_error`；
- **任务族完成判定**：主代理结束但子代理仍运行时（`subagentsActive`）不震动/不推送/不标未读，直到整组代理彻底结束；
- **内容展示**：HTML 块按原文呈现、纯二进制文件改为下载提示（不再显示「空文件」）、Markdown 音视频链接提供播放入口、工具执行中不被覆盖为「正在等待模型」。

---

## [1.7.72] - 2026-10-31

### 🚀 1:1 对标 pi-web 0.13.0：配置无损防坏、生命周期续租、提交与队列恢复闭环

- **模型配置保存安全（P0 级防护）**：
  - 依据 `app/api/models-config/route.ts` 规范，彻底移除「保存失败后换 `{config: ...}` 包装重试」的危险逻辑（实测在 0.13.0 会绕过服务端校验导致坏结构写盘）；
  - 错误信息优先透出服务端精准文案（如 `Provider needs a Base URL or at least one model`、图片字节上限错误、软链接失效等）；
  - models.json 往返序列化升级为无损合并：未在移动端建模的官方字段（单模型 `baseUrl`、`compat.forceAdaptiveThinking`、`inputLimits.images.resize.maxBytes`、provider 级的 `oauth`、`authHeader` 等）保存时 100% 完整保留，且删除项正常清理。
- **状态轮询性能飞跃**：
  - 会话运行时状态轮询切换至 `GET /api/agent/{id}?systemPrompt=0`（对齐 pi-web 0.13 `agentStatusUrl()`），大 `AGENTS.md` 项目单次轮询流量降低 90% 以上；
  - 权威 `running` 判定补齐 `isCompacting`，彻底解决手动/自动压缩中状态提前翻转为空闲的问题。
- **提交确认与发送草稿保护（#1457）**：
  - 支持上下文压缩期间发送消息：识别服务端返回的 `{queued: true}`，自动转入队列等待并在压缩结束后自动投递；
  - 发送失败保护：服务端明确拒绝时自动退回正文与附件到输入框，不破坏用户后续编辑；网络超时走对账确认，绝不盲目重复发送；
  - 会话目录不存在（`reason: "cwd-missing"`）给出明确路径提示；
  - Stop 与撤回排队消息时，服务端退回的正文与图片自动还回输入框（pi-web / TUI 行为对齐）。
- **会话生命周期与任务族完成（#1283、#1437、#1361）**：
  - 对齐 pi-web 0.13 新保活机制：每 30 秒向服务端发送 `POST /api/agent/{id}/lease` 续租请求，重置 10 分钟空闲倒计时；
  - 监听并展示异步 `prompt_error`，解析实时 `queue_update` 事件；
  - 任务族完成判定：主代理结束但子代理仍运行时（`subagentsActive == true`），只收尾流式正文，推迟完成提示音、震动与未读标记，直到整组代理彻底结束；过滤扩展命令对未读的干扰。
- **内容展示与体验提升（#1444、#1401、#1281、#1348）**：
  - 用户消息与 Markdown 中常规 HTML 块按源码如实呈现，防止 `<Component ... />` 蒸发；
  - 纯二进制文件识别：`.zip`/`.exe` 等返回 `binary: true` 时提示调用外部工具查看，不再呈现为「空文件」；
  - Markdown 中包含的音视频媒体链接渲染专属卡片，可一键调起内置播放器；
  - 工具执行中保护阶段标签，防止被覆盖为「正在等待模型」。
- **伴侣网关（Server）提示词增强能力落地**：
  - 网关新增独立 ESM 增强模块，实现非阻塞独立模型调用，支持代码块/路径/URL 保护与撤销栈管理；配套 24 个自动化测试全部通过。
- **单元测试守护**：
  - 新增 `ModelConfigRoundTripTest`、`ModelsConfigWriteContractTest`、`PromptSubmissionTest`，覆盖往返序列化、错误透出、排队回执及 lease 状态解析；纯 JVM 单元测试 24 passed 全部通过。

---

## [1.7.71] - 2026-10-31

### ⚡ 模型管理页缓存秒开 + 三处稳定性修复

- **秒开缓存（Stale-While-Revalidate）**：模型管理页先渲染内存/磁盘快照（按网关连接身份隔离，切换网关不会串用），后台再并发对账 `GET /api/models-config`、`GET /api/auth/providers`、`GET /api/models`（从 5 次请求降为 3 次），网络回来平滑替换并保持当前选中项不跳变；不再每次打开都整屏等一个居中加载圈，离线冷启动也能看到完整的提供商与模型列表；
- **修复冷启动闪退（高危）**：缓存文件字段类型异常时（例如在 raw JSON 编辑器里把 `baseUrl` 写成对象），反序列化会抛 `UnsupportedOperationException` 并冒到 `LaunchedEffect` 导致闪退。缓存层现在整体兜底，任何异常只退化为「没命中」，绝不影响启动；
- **修复同步异常闪退（高危）**：`syncModelsManagementData` 补齐 `try/catch`，模型配置合并阶段的解析异常统一转为可提示的失败，不再击穿到 UI 协程；
- **修复保存与同步竞态（高危）**：引入本地变更序号（`currentMutationSeq` / `storeSynced`），同步期间用户保存过配置时，更早发起的同步结果不再写回缓存，重启后不会回到保存前状态；缓存写穿失败也不再误报「保存失败」；
- **草稿保护**：后台同步回包若与本地未保存修改冲突，保留用户编辑并提示，不再盲接覆盖；
- **测试保障**：新增 4 个纯 JVM 用例（缓存损坏、字段类型异常、同步期间保存、无本地保存时正常落盘），`ModelsManagementCacheTest` 与 `ProviderConfigMergeTest` 共 18 个用例全部通过。

---

## [1.7.70] - 2026-10-31

### 🐛 修复通用 SVG 附件卡片白屏与宽度塌陷（消灭 flex width:auto 0px 漏洞）

- **白屏根因排查**：普通附件卡片 `SvgWebViewCanvas` 中使用 `display: flex; align-items: center; justify-content: center;`，但在子元素 `<svg>` 上指定了 `width: auto`。在 Chromium 内核下，若 SVG 自身带有 `width="100%"` 且无固定像素宽度，在 flex 容器中其固有宽度会被直接计算为 0，导致 SVG 尺寸彻底塌陷为 0px × 0px，直接呈现大白板；
- **全量修复与撑开**：移除 `width: auto`，强制指定 `display: block; width: 100%; height: auto; max-height: 92vh;`，经无头 Chromium 模拟验证已完全恢复正常渲染（宽度与高度实测正常撑开）；
- **全量注入设计系统令牌**：在通用 `SvgWebViewCanvas` 中同步注入 `widgetTokens(isDark)`，无论通用附件还是微组件，所有的 `c-teal`、`c-purple`、`box` 色阶类名均可正常命中，绝不再变黑块或白板。

---

## [1.7.69] - 2026-10-31

### 🚀 1:1 对标 pi-web：打通官方内置 Provider 凭据管理与模型合并展示

- **核心根因修复**：修复设置页「模型管理」此前仅孤立读取 `models.json` 磁盘文件，导致在 `auth.json` 中配置了有效 API Key 的官方托管提供商（如 DeepSeek、OpenAI、Anthropic 等）在模型管理界面彻底隐形的问题；
- **全量模型与提供商合并呈现**：`GlobalModelsConfigTab` 严格对齐 `pi-web` 架构，并行加载 `GET /api/models-config`、`GET /api/auth/providers` 与 `GET /api/models`，将已激活凭据的官方内置 Provider（如 `deepseek`）与自定义 Provider（如 `codywon`）统一合并展示，提供商总数与模型总数（2 个提供商，共 9 个可用模型）与会话切换面板 100% 对齐；
- **内置 Provider 专属运维交互**：
  - 为官方内置提供商增加「官方内置」专属标签，Base URL 自动显示为托管端点；
  - 隐藏针对内置 Provider 无意义的 Base URL 编辑与 API 发现按钮，支持直接管理/清除其在 `auth.json` 中的 API Key；
  - 单模型调校参数（如上下文窗口、最大输出等）采用官方标准的 `modelOverrides` 记录，绝不写入破坏性的单模型 `models` 数组，杜绝丢失官方模型目录的风险；
- **添加 Provider 面板体验对标 pi-web**：
  - 点击「+ 添加 Provider」支持「官方内置」与「自定义端点」双模式；
  - 官方内置模式自动列出 42 个已支持但未配置的厂商（Anthropic、Google、OpenAI、Groq、Moonshot 等），支持即时搜索，点击即可一键输入 API Key 快速激活；
- **单元测试保障**：在 `ProviderConfigMergeTest` 中补充对内置提供商 modelOverrides 无损转换、禁止写入裸 models 数组以及自定义提供商序列化的全量断言用例。

---

## [1.7.68] - 2026-10-31

### 🐛 修复用户提问带图乐观上屏缺失与快照对账丢图问题

- **提问附图即时上屏**：修复 `onSendMessage` 和 `onSendSteer` 追加本地乐观气泡时遗漏 `mediaList` 的问题，点击发送瞬间即刻渲染图片缩略图，消除用户对图片是否上传成功的疑虑。
- **快照对账保留媒体**：重构 `SessionSnapshotMerge`，加入零堆内存分配的流式双指针 Base64 等价判定算法，自动剥离前缀与换行，防止快照轮询因 Base64 格式差异误判导致图片闪烁重新解码；同时服务端带图时以服务端为准，快照临时空数据时保护本地乐观附件不被冲掉。
- **图片扩展名保底识别**：在附件选择器中补充 `.jpg`/`.png`/`.webp`/`.gif`/`.bmp` 等扩展名推断，解决部分定制系统文件管理器返回空 MIME 类型导致图片被误判为普通文件的问题。
- **模型与思考级别体验升级**：
  - 输入框上方胶囊升级为一体化分段双胶囊（Split-Pill），左侧点击弹模型选择，右侧点击弹独立思考等级底板（`ThinkingLevelBottomSheet`）。
  - 思考档位展示纯净中文标签与高档位（`high`/`xhigh`/`max`）琥珀橙警示色，并在切换不支持思考的模型时增加自动降级至 OFF 的安全防呆护栏与竞态保护。
- **清理隧道脚本硬编码**：移除 `start-server-tunnel.bat` 中的私有域名与隧道名硬编码，恢复公共 Quick Tunnel 开箱即用体验。
- **单测守护**：在 `SessionTreeNavigationTest` 和 `ThinkingLevelTest` 补充针对长 Base64 等价对账、媒体保留以及思考等级纯净标签等单测用例。

---

## [1.7.67] - 2026-10-31

### 🐛 修「SVG 架构图缩在卡片中间特别小」：去掉高度自我参照的死循环

**现象**：760×760 的架构图只占卡片宽度约 40%，四周大片白边；卡片本身也很矮，根本看不清。

**根因：卡片高度与内容高度互相咬住，是个死循环**：
- 卡片高度 = `实测内容高度 × 缩放比`；
- 但 `#root { min-height: 100vh }` 让「实测内容高度」**永远等于当前卡片高** —— 测量只能确认现状，永远无法纠正；
- 同时 `#root svg { max-height: 100vh }` 又把正方形 SVG 压到卡片**自身高度**上。

于是：卡片矮 → SVG 按矮边缩小 → 测量“确认”矮 → 卡片还是矮。把 760×760 图压成 40% 宽、四周白边。

**改法（宽度决定一切）**：
```css
#root       { display: flex; align-items: center; justify-content: center; }
#root > svg { display: block; width: 100%; height: auto; }
body        { box-sizing: border-box; min-height: 0; }
```
- SVG 永远撑满卡片宽度，高度自然等于 `宽度 × 宽高比`；
- 实测回填后 `previewHeightDp` 算出的正是这个值，两张公式一致 —— 760×760 得到近乎满宽的方图；
- 高度上限从 460dp 放宽到 **560dp**（约 0.6 屏），纵长图（1:2 / 1:3）不再被截掉底部。

这与社区/参考实现的做法一致：`claude.ai` 产物 iframe 与 `pi-generative-ui` 的 Glimpse 窗口
都是**容器高度跟随内容**，从不拿容器高度反过来限流内容。

**单测**：新增 2 项断言（SVG 必须 `width:100%/height:auto` 且不得出现任何 100vh 耦合；
body 不得用 vh 抬下限），高度夹紧用例同步到 560。单测合计 **42 项全绿**。

---

## [1.7.66] - 2026-10-31

### ✨ SVG 生成式微组件支持保存与导出：PNG 相册即开 + 自包含 SVG 下载（对标 pi-generative-ui）

**背景**：SVG 架构图与流程图生成后无法快速保存到手机相册或导出分享，极大限制了移动端查阅与交付体验。

**改法**：
1. **右侧操作组**：当微组件为 SVG 且生成完成时，在卡片标题栏右侧（全屏图标旁）及全屏顶栏展示「下载」图标；
2. **下载选项（两级分层）**：
   - **保存图片 (PNG) [主选项]**：将渲染的位图直接保存到系统相册（`Pictures/piDroid`），微信/系统图片选择器立即可见，方便一键发给好友或团队；
   - **保存矢量图 (SVG) [副选项]**：导出单文件 `.svg` 到下载目录（`Download/piDroid`），供设计软件二次编辑；
3. **自包含防黑块注入**：导出的 `.svg` 文件并非简单丢出裸标签，而是自动在 `<svg>` 内部注入与当前 App 深/浅色主题匹配的完整色阶 `<style>` 定义与背景底色矩形，保证导出的矢量图无论发到哪台电脑或第三方工具打开，**绝不再回退变黑块**；
4. **单元测试守护**：在 `GenerativeUiTest` 中增加独立自包含 SVG 序列化、文件名安全清洗等 2 项核心回归断言（单测扩充至 40 项全绿）。

---

## [1.7.65] - 2026-10-30

### 🔌 修「获取会话失败 / 超时」：只读请求 12s 硬超时 + 自动换新连接重试一次

**先把现场量了一遍**（2026-10-30，本机实测，不是推测）：

| 路径 | 实测 |
| --- | --- |
| 直连 pi-web `/api/sessions` | 10–25ms（`?force=1` 冷重建也只有 57–67ms） |
| 经固定隧道 `https://pi.codywon.top` | 303KB → **gzip 81KB**；连续 30 次 **30/30 成功**，p50 **1.45s**、p90 **2.6s**、max 3.8s |
| 64MB 会话的快照 | 直连 1.1s（要解 64MB jsonl），隧道 4.4s，线上 109KB |
| 空闲 75s 后复用同一条 TCP | 依然 1.8s 成功 |

结论：**服务端、隧道、带宽都不是瓶颈**，能到 30s 的只有「单条连接被静默弄丢」：
隧道重建 / 基站切换 / 运营商 NAT 回收旧映射时，旧 socket 不发 RST，请求就写进黑洞。
而 OkHttp **不会重试读超时**（`isRecoverable` 对 `SocketTimeoutException` 且请求已开时直接放弃），
于是旧代码只能干等到超时，把 `timeout` 原文摔给用户。

**四处修复**

1. **只读 GET 统一出口 `readGet()`**：单次 `callTimeout` **12s**（弱网 4G 对 81KB 有 7 倍余量；
   首屏会话快照因为要解完整个 jsonl——本机最大会话 64MB——单独给 30s），
   传输层失败（超时 / 连接重置 / 半开连接）**自动重试一次**，且重试前 `connectionPool.evictAll()`
   踢掉可疑旧连接 —— 第二次一定是新连接，不会在同一个死 socket 上再超一次。
   覆盖：会话列表 / 运行态 / 会话快照 / agent 状态 / 会话模型探针 / ui-state 六个只读接口。
2. **HTTP/2 ping**（`pingInterval = 20s`）：经隧道是 h2，ping 让 OkHttp 自己发现半开连接，
   同时把运营商 NAT 映射踩醒 —— 旧连接在「被用上」之前就被淘汰，而不是用上之后才等超时。
   （HTTP/1.1 局域网直连下无副作用；SSE 用的是独立连接池，不受影响。）
3. **列表自动重拉加 15s 地板**：实测列表 303KB/81KB，而 agent 每个回合都会 bump
   `sessionListVersion`；以前逐次重拉会把手机链路长期占着。手动刷新不受限。
4. **失败文案可执行 + 可排查**：不再显示 `timeout` 原文，改用统一的
   「网络超时或中断（已自动重试一次），请检查网络或网关地址」；同时在 logcat 打
   `PiNet`（路径 / 耗时 / 异常类型 / 重试轮次，慢请求 >5s 也会记一条），下次再出问题能直接看出是哪一段。

**效果**：绝大多数「瞬间断一下」的请求用户不再感知（重试就回来了）；真正不通的会在 ~12–24s 内
给出一句能照着做的提示，而不是几十秒的无尽转圈。

## [1.7.64] - 2026-10-30

### 🎨 修复 SVG 架构图全变黑块：注入 Claude.ai / pi-generative-ui 完整设计系统色阶（深浅色自适应）

**现象**：模型生成的架构图/流程图在手机端呈现为几个实心黑色矩形，所有文字和边框被淹没。

**根因**：
- 模型输出的 SVG 严格遵循 Claude.ai 规范，使用 `class="c-teal"`、`class="c-purple"`、`class="box"` 等语义类名，**不包含任何内嵌 fill 属性或 `<style>`**；
- 按照 W3C SVG 规范，无 fill 且无 CSS 规则命中的 `<rect>` 与 `<text>` **一律回退为默认纯黑 (#000000)**；
- 手机端 WebView 外壳此前缺少这套设计系统样式表，导致所有框体与文字全部回落成黑色，黑字写在黑块上，直接变死黑块。

**改法（方案 1：深浅色自适应）**：
- 注入完整的 9 组语义色阶（purple, teal, coral, pink, gray, blue, green, amber, red）与基础元件类（box, node, arr, leader, th, ts, t）；
- **浅色模式（当前）**：注入 Anthropic 官方规范的 50 级淡彩底色（如淡绿 `#E1F5EE`、淡紫 `#EEEDFE`）+ 600 级实色边框 + 800 级深色文字，白底呈现清爽高雅的白纸彩墨质感；
- **深色模式**：1:1 注入 PC 端 `pi-generative-ui` 同款深色令牌（800 级暗色底 + 400 级亮色边框 + 100 级发光字），呈现高对比度暗黑极客风；
- 补齐单元测试 `GenerativeUiTest`（浅色与深色色阶注入断言）。

---

## [1.7.63] - 2026-10-30

### 🐛 修生成式 UI 卡片「正文每输出一行就跳一下」

原因不是渲染，是**版面顺序**加**滚动方式**两处叠加。

**① 流式正文被放进了卡片上方的过程折叠组**

一个回合的版面顺序是「用户提问 → 过程折叠组 → 微组件卡片 → 最终答复」。
但「正在运行」时最后一条 assistant 正文会被按 pi-web 的口径降级成中间过程、塞进折叠组，
于是**正文长在卡片上方**：每多一行就把卡片往下推一行。等回合结束，这段正文又被提成最终答复
挪到卡片下方 —— 又一次跳位。

改法：`buildTurns` 里当回合含微组件（`turnHasWidget`）时，不再把最后一条 assistant 正文降级，
直接作为独立的最终答复行渲染。这样流式期的版面顺序与完成后的稳定态完全一致，卡片只会在自己
的位置上待着，正文只在它下方生长。**不含微组件的回合保持原有 pi-web 行为不变。**

> 顺便回答「要不要等文字输出完再呈现 UI」：不需要，也不好。
> 对标对象 `pi-generative-ui` 的微组件开在**独立原生窗口**里，终端正文怎么流都与它无关，
> 所以它给不出「内嵌时怎么办」的规则。放进会话流之后，需要的是「正文不能在卡片上方生长」
> 这个版面不变量，而不是延迟呈现。

**② 流式跟随是「瞬间滚到尽头」，每个 chunk 都猛拽一次**

自动跟随原本用 `scrollBy(6000f)` 循环硬滚到底，而流式正文每秒触发十几次 —— 整屏被反复猛拽，
读起来就是正文和卡片在抖。

改法：新增 `followToBottomSmoothly()`，用 `animateScrollBy(视口高)` 平滑跟随。滚动会被 clamp 到
真实剩余距离，所以内容只多一行时它就只平滑移动那一行，不会过冲；一次性插入一张大卡片时才需要
多步循环。用默认 spring 而非 tween：该动画会被下一段增量的重组反复重启，spring 会带上当前速度
继续，tween 则每次从头缓入、看着更顿。

**文档**

- `DESIGN-generative-ui.md` §3.2 增加「版面不变量」：含微组件的回合，流式正文必须在卡片下方；
  并记录为什么 pi-generative-ui 给不出这条规则。§3.7 补上滚动侧的第二抖动源与平滑跟随的理由。

---

## [1.7.62] - 2026-10-30

### 🐛 PC 端切模型/供应商，手机端真正跟上（新增 1.9KB 模型探针）

先拿正在跑的网关实测了一轮（会话 `01a188b1…`）：

- `POST /api/agent/{id} {"type":"set_model",…}` 成功，`/context`、`/state`、`get_state` 立即变成新值；
- 但 **SSE 事件流里什么也没有**（只收到 `connected`；切思考档才有 `thinking_level_changed`）。
  即 pi-web 不推模型变更事件，手机端只能自己拉。
- 而 App 之前只在「全量快照」里读模型，节流到**空闲 15s / 在跑 10s**；模型面板打开时更是什么都不拉 ——
  所以 PC 上切完，手机上看上去就像“没同步”。

修法（全部基于实测体积/行为）：

**① 3s 轻量探针代替等快照**

- 新增 `GET /api/sessions/{id}/context?tail=1`（实测 **1898 字节**，带 `context.model` + `thinkingLevel`；
  对比全量快照 267KB、`/state` 25KB）→ 进同步循环，每轮与当前选中比对，变了就更新并落盘身份。
- 与本地切换同一套护栏：请求前后比对 `modelMetaRevision` + `pendingModelMetaChanges`，
  用户刚在手机上切的不会被在途探针盖回去。

**② 打开模型面板 = 立即对账**

- 面板打开时立刻拉一次模型目录（`/api/models`，2.9KB，`modelsOnly` 不拖技能/插件/MCP）
  与当前会话模型（1.9KB），所以 PC 上新加的供应商/模型、刚切的模型，一打开就能看到。

**③ 同步循环不再可能被单次异常弄死**

- 每轮包 try/catch（`CancellationException` 仍然上抛）；以前一次解析/网络异常会让这个
  `while` 直接退出，表现为“正文因为 SSE 还在动，但模型/运行态永远冻结”。

**工程**

- 新增 `SessionModelRefParser`（纯逻辑，+8 项校验）：只半边身份（缺 provider 或 id）一律拒识，不开半更新。
- `SessionModelRefParserTest` 覆盖真实探针 JSON、PC 侧换供应商、`id` 兼容字段、垃圾响应。

## [1.7.61] - 2026-10-30

### 🐛 密钥失效不再是「静默 401 刷屏」：停轮询 + 明确提示 + 一条机器可读的 401 标记

网关日志里那一大片 `[auth] 401 ... token=present(len=35)` 的来源找到了：轮换 Token 后
App 手里还是上一轮的值，而各条轮询（`/api/agent/running` 每 2.5s、会话页另外几条）都用
`getOrNull()` 把错误吞掉 —— 于是网关满屏 401，界面上只表现为「怎么都不更新」，没有任何出路提示。

**① 网关：401 带机器可读标记，日志不再被扫描器挤掉**

- 401 响应固定带 `WWW-Authenticate: Bearer realm="pi-gateway", error="invalid_token"`
  与响应体 `{"code":"TOKEN_INVALID"}`；WebSocket upgrade 的 401 同样带这个头。
- `auth-fail.log` 的 3s 节流只作用于「压根没带 token」的请求（公网扫描器一天几十万条）；
  **带了 token 却不匹配的永远记** —— 以前是全局节流，扫描器刷屏时恰好会把最要紧的那几条挤掉。

**② App：只有网关的标记才算「密钥失效」**

- 新增 `AuthRejection`：判定条件是 `401 + invalid_token 标记 + 这次用的是本地存的 Token`。
  pi-web 在**模型 API Key 填错**时也返回 401（`Invalid API key`），那属于去清 Key，
  误判会把用户往「重新扫码」的死路上推；配对对话框里手填/扫码的候选 Token 试连失败
  也不会把整个 App 判成失效（否则正在用的会话会被停掉）。
- 新增 `AuthRejectionSignal`：置位后列表页/会话页的轮询循环直接退出，弹一次
  「密钥已失效，请重新扫码配对」；任何一次成功请求（含重扫后的试连）自动清零，
  轮询自动恢复，并把因为 401 停掉的 SSE 重新挂上（不用退出会话再进来）。

覆盖 `server/logs/auth-fail.log` 里历史的 107 条样本做了复盘：31 条 `present(len=35)` 全部来自
`okhttp/4.12.0`、集中在轮换后的 2 分钟内（`fp` 与 `expected` 不同）；其余 76 条 `token=missing`
是伪造 UA 随机路径的公网扫描，网关返 401 是正确行为。

## [1.7.60] - 2026-10-30

### 🐛 修「回到会话又变回上一家供应商」：本地快照不得再决定模型身份

1.7.57 修的是「选择器 ✓ 只比 modelId」（同名模型两行都勾上），这次接着查真正的回弹源。
直接拿你正在跑的网关实测了截图里那条会话（`01a188b1…`）：

- `GET /api/sessions/{id}` → `context.model = {provider: deepseek, modelId: deepseek-flash}`
- `GET /api/sessions/{id}/state` → `state.model = {provider: deepseek, modelId: deepseek-flash}`
- `POST /api/agent/{id} {"type":"get_state"}` → 同一个身份

也就是**服务端早就切到 deepseek 了，codywon 只可能是 App 本地旧数据变出来的**。三条路堵死：

**① 缓存快照不再刷模型身份**

- 根因：`applyHistoryMeta`（内存/磁盘快照回放）会把快照里的 `provider/modelId` 写进界面。
  磁盘快照有 10s 写节流、只留 8 个，回到会话时先回放的往往是**切换之前**那一份 → codywon。
- 改法：缓存只回放正文/统计/游标，模型身份只认「本机刚切的选择」与「服务端网络快照」。

**② 按会话记一份「最后确认的模型身份」**

- 切换成功、以及每次服务端对账，都把 `provider/modelId` 落盘（`SessionModelIdentityCodec`，上限 300 条）；
  打开会话先用它还原，不再先拿全局默认或上一条会话的模型顶上。

**③ 输入框上方的胶囊直接写出供应商**

- 仅在「同一个 modelId 同时挂在多个供应商」时拼上 provider，例如
  `DeepSeek V4.1 Flash · deepseek · max`，一眼可验，不用开面板猜。

**工程**

- 新增 `SessionModelIdentityCodecTest`（7 项）；纯逻辑校验（含真实会话数据同构用例）全绿。

## [1.7.59] - 2026-10-30

### ✨ 生成式 UI 内嵌改成位图缩略图：从结构上消除频闪，并按实测高度消除底部裁切

两个现象都不是参数问题，是**承载方式**错了。

**① 频闪 —— 内嵌不再放活 WebView**

之前内嵌是一个活的 WebView，它会在流式补字、列表自动滚动、item 回收时被反复重排/重建。
前几版试图用「生成期占位」「离屏快照」去盖住它，都不解决根本。

现在：**渲染一次 → 抓成 `Bitmap` → 立刻销毁 WebView → 卡片只剩一张图**。
位图不参与任何布局与重绘，从结构上不可能闪；顺带每张 widget 不再常驻一个 Chromium 实例，
滚动时的内存与掉帧问题一并消失。

**② 底部裁切 —— 不再相信模型声明的 `height`**

模型经常**少报**高度：真机上声明 `900×900`，实际内容却高出几十像素，照声明裁剪就必然差一截。
现在用**实测内容高度**（`document.body.scrollHeight` 与 `#root` 矩形高度取大）乘缩放比
得到卡片高度，夹在 `120~460dp`。

关键约束：**抓图必须等实测高度到齐再抓**。否则会抓到「按估计高度裁剪」的残缺版并缓存住，
之后永远不纠正 —— 实测高度到达会改变卡片高度、重启抓图 effect，自然重抓。
抓图失败（少数机型首帧拿不到像素，抽样像素全同即判空白）最多重试 4 次，
仍失败退回活 WebView，行为等同旧版，不会白屏。

**③ 顺手做的结构整理**

- WebView 构建抽成共用的 `createWidgetWebView(...)`，全屏与缩略图两条路共用一套安全设置
  （无 JS 桥、禁文件访问、外链交系统浏览器、CDN 命中 assets）。
- 新增 `WidgetThumbs` 位图缓存（LRU 3，宽度上限 1080px，约 3.9MB/张）。
- 内嵌缩略图可点击 → 直接进全屏（位图没有页内交互，点击不再有歧义）。
- `docs/DESIGN-generative-ui.md` 重写 §3.1 / §3.4：记录「位图而非活实例」的理由、
  抓图时序约束，以及被移除的活实例 + 快照叠层方案。

---

## [1.7.58] - 2026-10-30

### ✨ 生成式 UI 内嵌改成「缩略图看全局」：不再裁切，点开看大图

按实际使用体验重定方向：**内嵌不再试图把 widget 撑到原生尺寸，而是一张看全局的缩略图**，
点进去再看大图。

**之前为什么怎么调都只显示一部分**

内嵌让内容「回流到手机宽度」。但模型常用横排多栏（主卡 + 24 小时侧栏），回流到 360dp 时：

1. 侧栏被压成一条竖排文字，不可读；
2. **内容高度膨胀到声明值的两倍以上**（`900×920` 的图回流后接近 2000dp）。

只要内容回流，第 2 条就决定了「无论给多高的框都会裁掉一部分」。前两版分别试过
「按声明比例算框」和「实测 `#root` 高度回填」，都是在错误前提上打补丁。

**现在的做法**

- 页面**按模型声明的桌面宽度布局**（不再回流），再用 `initial-scale = 容器宽 / 声明宽`
  整体缩放。这样 `payload.height` 就是模型自己在该宽度下排出来的真实高度，等比缩放后
  **整张图天然完整可见** —— 不需要测量，也不会裁切。
- 卡片高度 = `声明高 × 缩放比`，夹在 `120~460dp`（超高的图不至于撑成几屏，超扁的不至于一条线）。
  900×920 在 387dp 宽容器里约 395dp。
- 全屏沿用同一布局，占满整屏 + 可滚 + 可双指放大，即「看大图」。
- 两种模式都依赖 WebView 的 **`loadWithOverviewMode = true`**：设为 false 时它会按声明宽度
  布局却**不缩出去**，这正是上一版「全屏只看到左边一半」的直接原因。

**顺带删掉的复杂度**

- 整块「实测高度」机制移除：`onContentHeightPx`、`WidgetHeights` 缓存、`getBoundingClientRect`
  回填全部删除。高度现在是 `payload` 的纯函数，天然稳定 —— 不再有「回收后高度重置 → 列表重排
  → item 被挤出视口 → 再测一次」的抖动链路。
- `GenerativeUiTest` 相应改写并新增：内嵌必须按声明宽度布局（不是 `device-width`）、
  缩略图高度 = 声明高 × 缩放比且两端夹紧、缩放比等于容器宽/声明宽且不外溢。
  单测合计 36 项全绿。
- `docs/DESIGN-generative-ui.md` 记下被推翻的三种方案，避免再走回头路。

---

## [1.7.57] - 2026-10-30

### 🐛 修「换供应商切模型看不出变化 / 同名模型串供应商」

根因不是没把 provider 发出去（`set_model` 一直是 `provider + modelId` 成对发送的），
而是**模型身份在本机被拆成了半个**：同一个 `modelId` 可以同时挂在多个 provider 下
（`deepseek-flash` 在 `codywon` 与 `deepseek` 各有一份），而选择器只比 id。
逐条对照 pi-web 的 model-selector 修掉：

**① 选择器 ✓ 改成 provider + modelId 成对判定**

- 根因：`isSelected = model.id == selectedModelId`。两行同名模型会同时出现 ✓ 和高亮，
  切到另一家供应商后界面像素级不变 —— 用户只能理解为「没同步 / 串了」。
- 改法：与 pi-web 一致（`active: e.modelId===m.modelId && e.provider===m.provider`）
  新增 `SessionModelSelection.isSelected/matches`，面板接收 `selectedProvider`；
  默认模型的星标同样按这一对判定。
- 顺带对齐 pi-web 的搜索：`codywon/deepseek-flash` 这种 `provider/modelId` 写法也能搜到。

**② models.json 覆盖不再写到别人的同名模型上**

- 根因：`applyModelOverrides` 三处定位都是 `精确命中 ?: 按 id 兜底`。给 B 供应商存的
  上下文窗口 / 最大输出 / `provider` 字段会落到 A 供应商的同名模型上 —— 真正的串供应商。
- 改法：新增 `ModelCatalogMerge.indexOf`：精确命中优先；id 在目录里唯一时才允许跨 provider
  回退；重名时直接跳过，宁可不生效也不写错对象。

**③ 切完模型不再被旧快照弹回上一个供应商**

- 切成功后立刻把内存会话快照的 `(provider, modelId)` 一起改写，重进会话不会先用旧快照回放。
- 切换成功的提示在模型名重名时补上 provider，一眼确认切的是哪一家。

**工程**

- 新增 `ModelCatalogMergeTest`，`SessionModelSelectionTest` 补同名不同供应商、
  未知 provider 回退、重名判定等用例；纯逻辑校验 23 项全绿（用项目同版本 kotlinc 2.0.21 直编源码验证）。

## [1.7.56] - 2026-10-30

### 🐛 修生成式 UI：频闪 / 预览区仍只露一半 / 全屏没缩进屏宽

上一版（1.7.55）的三处修复都建立在三个错误判断上，真机逐条对照后重做。

**① 频闪 —— 移除了「离屏快照」叠层**

- 根因：销毁前抓一帧、回来先贴图再重建。快照是**旧帧**，页面画好后换成实时内容，
  两者不一致就是一次跳变；滚动补位期间反复发生 —— 不是"闪白"，是"闪跳"。
  它只掩盖了几百毫秒空白，重建与重跑页面的真实成本一点没省。
- 改法：直接删掉快照机制（`WidgetSnapshots` 整个移除）。实例数本来就跟着视口走
  （`LazyColumn` 会 dispose 离开视口的 item），不需要额外回收。
- **彻底解决要 WebView 实例池**（按内容哈希驻留 2~3 个已加载实例），
  需逐机型验证，本版不做，已写入 `DESIGN-generative-ui.md` 的已知问题。

**② 预览区仍只露一半 —— 高度测量一直被跳过**

- 根因：我用 `url == "about:blank"` 跳过销毁时的清理加载，但 `loadDataWithBaseURL(null, …)`
  加载出来的页面 URL **本身就是 `about:blank`**，于是整个 `onPageFinished` 函数体
  （白板检测 + 高度测量）从来没执行过，高度永远停在按声明比例的估算值。
- 改法：用显式的 `disposed` 标志判断，不用 URL。
- 另修一处量纲错误：WebView 在 zoom=1 时 **1 CSS px == 1 dp**，早期又按 density 除了一次，
  高度会被算小到 1/3。
- 实测高度改存到**组合之外**的 `WidgetHeights`（LRU 32）：`LazyColumn` 回收 item 会连同
  item 内的 `remember` 一起丢掉，高度退回估算值 → 卡片高度一变、列表重排 → 可能又把 item
  挤出视口 → 再测一次，来回抖动。

**③ 全屏没缩进屏宽 —— `width=900` 只改布局宽度，不改缩放**

- 根因：`loadWithOverviewMode = false` + viewport `width=900`，WebView 按 900px 布局
  **但不缩出去**，所以只看到左边一半（右侧「未来 24 小时」整块被切、「成都」被切）。
- 改法：把初始缩放比显式写进 viewport —— `initial-scale = 屏幕宽 / 声明宽`
  （411/900 ≈ 0.457），整张图等比缩进屏宽，细节靠双指放大；异常值（0 / NaN）退回 1.0。

**工程**

- `GenerativeUiTest` 补 2 项：全屏必须写 fit-to-width 的 initial-scale；非法缩放比退回 1.0。
  单测合计 34 项全绿。
- `docs/DESIGN-generative-ui.md` 同步：§3.4 重写（说明快照叠层为什么被删、高度为什么必须
  存在组合之外）、§3.5 记下 `about:blank` 这个坑、§3.6 补 initial-scale 要求。

---

## [1.7.55] - 2026-10-30

### 🐛 修生成式 UI 真机三宗罪：闪动 / 只显示一半 / 全屏右侧看不到

上一版（1.7.54）把微组件挪进了会话流，但三种现象都是**设计前提错了**，不是实现细节：

**① 闪动：生成期表里一直在滚动，WebView 反复销毁重建**

- 根因：生成中回合还在跑，列表持续向下自动补位，卡片正好在视口边界上反复进出组合；
  `LazyColumn` 会 dispose 离开视口的 item → WebView 销毁 → 回来时新建并 `loadDataWithBaseURL`
  重新加载页面。另外 `holder` 写成 `mutableStateOf` 并在 factory 里回写，每次创建又额外触发一轮重组。
- 改法：`isRunning` 时只显示占位行，工具调用结束再挂一次 WebView；`holder` / `loadedKey`
  改成非可观察的 `AtomicReference`，不再在创建阶段触发重组。

**② 只显示一半：把桌面声明高度当成了内容高度**

- 根因：`previewHeightDp()` 用 `360 × 880/900 ≈ 328 → 夹到 320dp`。但 `880` 是它在**900px 宽**下的高度；
  手机按 360dp 回流后同样内容变成 1400dp 级 —— 按错误前提算出来的框必然装不下。
- 改法：页面加载完后**实测** `#root.getBoundingClientRect().height` 回填给 Compose，
  夹在 `[180dp, min(0.6×屏高, 480dp)]`，且只回填一次（反复改视口高度会触发微组件自身重排，表现为抖动）。

**③ 全屏右侧看不到：两处都把滚动锁死了**

- 根因：`buildWidgetHtml()` 把 `overflow: hidden` 写死，全屏页同样继承；而全屏传的是
  `allowVerticalScroll = false`。两个都锁死 → 超出视口的内容**既不能滚也不能缩**。
  右侧那条被压成竖排文字的「未来24小时段无降水」不是消失，是被 360dp 窄宽度挤扁的侧栏。
- 改法：引入两种布局模式——
  - **内嵌**：`width=device-width` + `overflow:hidden`（控件要大到能点能拖，纵向手势给列表）；
  - **全屏**：`width=<声明的桌面宽度>` + `overflow:auto`（WebView 按 900px 布局后等比缩放到屏宽，
    完整还原模型的设计意图，可滚、可双指放大）。

**工程**

- `GenerativeUiTest` 补 3 项并改写 1 项：全屏按声明宽度布局、全屏可滚/内嵌不可滚、内嵌仍然回流。
- `docs/DESIGN-generative-ui.md` 更新：「固定高度预览区」这条已被真机推翻，改为实测高度；
  新增 §3.6 两种布局模式、§3.7 生成期不挂 WebView，并把「快速滑动时仍会重建重载」
  作为已知剩余问题写进去（彻底解决需 WebView 实例池，需逐机型验证）。

---

## [1.7.54] - 2026-10-30

### ✨ 生成式 UI：微组件从「要先点开的过程」变成「会话里直接能玩的图」

上一版（1.7.49）把 `show_widget` 接到了手机端，但只给了一行文字入口卡，而且要先进「过程详情」才找得到。
这一版按 **claude.ai 的形态**重做承载层（依据 `docs/DESIGN-generative-ui.md`）。

**① 微组件是内容，不再藏在过程折叠组里**

- 根因：`show_widget` 混在 `processItems` 里，而 `ProcessDetailsGroup` 在回合答完后会自动收起。
  于是要看图得点**两级**：先展开「N 条消息 · M 次工具调用」，再点卡片。
- 改法：新增 `TurnOutline.widgetItems`，微组件独立成行，排在过程组之后、最终答复之前。
- 顺带：`visualize_read_me` 不再计入「M 次工具调用」——报一个用户看不见的调用与所见不符。

**② 卡片里就是那张图，可以直接拖滑块**

- 固定高度预览区，高度按模型声明的桌面宽高比换算后夹在 180~320dp（800×600 在 360dp 屏上约 250dp）。
- 全屏入口只放标题行右侧（⛶）。**不做「点预览区就全屏」**：那会把页内点按钮变成切换页面。

**③ 手势按主轴归属**

- 页面设 `overflow: hidden`，内嵌态永不自滚；`DOWN` 先声明独占（避免滑块拖到一半被列表抢走），
  首个明显纵向的 `MOVE`（>16px）主动交还列表。
- `docs/DESIGN-artifact-viewer.md` 里「WebView 不能内嵌」那条针对的是需要自身纵向滚动的长内容
  （docx / 代码 / 长图），微组件是固定尺寸组件，不适用。

**④ 离屏快照：像 iframe 一样回收，不爆内存**

- `LazyColumn` dispose 掉滑出视口的 item 前，先抓一帧存进 `WidgetSnapshots`（LRU 8 条，宽 ≤720px）；
  滑回来先贴快照、再由新建 WebView 顶上去，没有白屏闪烁。
- 如实说明：缓存的是**渲染结果**不是交互态，滚回去后页内临时状态会回到初始值。

**⑤ 白屏不可接受：三重哨兵 + 自动降级**

- 页面内 `window.onerror` / `unhandledrejection` / 捕获阶段的脚本加载失败监听
  （外链脚本 404 不触发 `window.onerror`，必须看 `event.target`），统一以 `__PI_ERR:` 前缀回报；
- `onPageFinished` 检查 `#root` 子节点数，为 0 视为白板；
- 主文档 `onReceivedError`。
- 命中任一条 → 自动切源码视图（等宽代码块 + 重试），不新增解释性副标题。

**⑥ 离线可用：Chart.js 本地化**

- `shouldInterceptRequest` 拦下 cdnjs / jsdelivr / unpkg / esm.sh 上已知文件名，改由 `assets/libs/` 回吐
  （只做 allowlist，`mermaid` 压缩后 >1MB 不本地化）。弱网与离线时图表仍能渲染。

**⑦ 不需要手工调度脚本顺序**

- 走整篇文档加载：浏览器按文档顺序同步执行脚本，普通外链脚本还会阻塞解析到下载完成，
  所以 `Chart` 必定在内联脚本之前就位。参考实现里那套 `runScripts()` 是为流式追加片段准备的，这里用不着。

**工程**

- 新增 `docs/DESIGN-generative-ui.md`：写明「参照 claude.ai 而非 pi-web」、手势/回收/降级三条边界，
  以及流式接入时必须遵守的两条（`_delta` 可丢弃 → 终态必须全量兜底；`delta` 是原始 JSON 分片）。
- 新增 `WidgetCodeStream` + `WidgetCodeStreamTest`（14 项）：为流式做好了增量提取，本次**未接线**。
- CI 加单测门禁：`.github/workflows/build-and-release.yml` 在打包前跑 `:app:testDebugUnitTest`。
  此前 `GenerativeUiTest` 在流水线里一次都没被执行过。
- `GenerativeUiTest` 补 5 项：预览区高度换算与夹紧、错误哨兵与 `#root`、`overflow` 关闭自滚。

### 🧱 修技能中心「添加技能」搜索栏过高、与搜索按钮不在一个水平线上

**问题**：`OutlinedTextField` 自带 56dp 最小高度 + 内部上下留白，旁边那颗 `Button` 只有 ~36dp，
两个控件靠 `verticalAlignment=Center` 勉强居中，视觉上输入框高一大截、按钮往下坠，
并且输入框圆角/边框/底色与同屏幕「已安装」页 44dp 工具条完全不同族。

**改法**（与已安装页工具条统一为同一套视觉语汇）：

1. **弃用 `OutlinedTextField`，改 `Surface + BasicTextField`**：固定 **44dp** 高、`RoundedCornerShape(9.dp)`、
   `surfaceContainerLow` 底 + 1dp 边框（有输入时边框转主色），与已安装页搜索框同规格；
   放大镜 15dp、文字 12.5sp、清除按钮 14dp，单行居中对齐，不再有任何 Material 内建垂直留白。
2. **搜索按钮同高同圆角（44dp / 9dp）**：与输入框基线严格对齐；禁用态不再用「半透明主色」
   （看起来像脏色块），改为中性 `surfaceContainer` + 边框 + 弱化文字；加载态只留 14dp 进度圈，
   文案里的「搜索中」去掉，避免按钮在两种宽度之间跳。
3. **键盘直接回车即搜**：`imeAction = ImeAction.Search` + `KeyboardActions(onSearch)`，
   不用再收键盘去点按钮；占位文案改为「搜索 skills.sh 技能，如 react、testing」并按单行省略，
   窄屏不再被长占位文撑破。

## [1.7.52] - 2026-10-30

### 🌀 修「进会话一直同步不下来、右上角一直转圈」：把同步从「排队」变回「按需」

**病根不是网络，是流量自己把自己的路堵死了。**实测（PC 上直接打网关，同一份数据）：

| 接口 | 实测体积 | 旧节奏 | 折算 |
| --- | --- | --- | --- |
| `GET /api/sessions`（418 个会话 / 55 个工作区） | **298 KB** | 列表巡检每 2.5s，版本变了就整份重拉，且**没有并发闸门** | 巡检 + 返回列表 + 手动刷新可叠 2–3 条同时在飞 |
| `GET /api/sessions/{id}?tail=60&tree=summary` | **230–435 KB** | 只要在跑就 **1.5s** 一次 | ≈150 KB/s 常驻 |
| `GET /api/sessions/{id}/state` | 26–32 KB | 同上，1.5s 一次 | |

一条 300KB 请求经手机 + Cloudflare 隧道要 1–3s，而请求每 1.5s 就发一次 —— 于是正文（SSE）、
列表刷新、首屏快照全在排队。**这解释了「一会儿好一会儿坏」**：网络好、PC 空闲、会话短的时候每条
几百毫秒就回来了，一切正常；一旦信号切换 / 隧道抖动 / PC 上同时有几个任务在跑，队列就再也追不上，
表现出来就是「一直同步不下来」。

**四个具体修复**

1. **快照节奏对齐 pi-web（核心）**：新增 `SessionSyncCadence`（纯 JVM，附单测）。
   pi-web 自己的 `useAgentSession` **不在运行中轮询正文**，只在回合边界（`agent_end` /
   `agent_settled` / `prompt_done` / 事件流重连）重读快照。现在 App 也这样：
   - 空闲 → 15s；在跑 + 事件流健康 → 10s 安全网（正文交给 SSE）；
   - 在跑 + 流断了 / 静默 → 收紧到 **3s** 用快照兜底补正文；
   - **运行态翻转（开始/结束）或列表版本变化 → 立刻对账**（不等最小间隔，所以「任务结束」不会被漏掉）。
   「事件流健康」窗口取 **35s**：必须大于 pi 的心跳周期（30s），否则两个心跳之间的空白会被
   误判成断流，让限速白做。
2. **加载态必须有出口**：
   - `loadSessions` 改为**单飞 + try/finally**。以前任何未预料的异常都会直接终结协程，
     `isLoadingSessions` 永远停在 true —— 右上角那个圈就再也停不下来；
   - `syncSessionSnapshot` 补上 `histResult.onFailure`（以前**完全没有**失败分支）：
     正文拉取失败时退出首屏加载态并提示一次「会话同步失败」，不再是一个永不结束的圈；
   - 上滑补页 `isLoadingOlder`、手动压缩 `isCompacting` 同样补 finally。
3. **只读轮询加 30s 硬超时**：新增 `readClient`（`callTimeout`），会话列表 / 运行态 / 会话快照 /
   agent 状态 / ui-state 五个幂等只读请求走它。原来只有 `readTimeout=120s`，隧道半死时一次请求能
   拖成两分钟「没有进展的转圈」；写操作（发消息 / 压缩 / 生成标题）仍走原 `client`，不受影响。
4. **并发闸门**：全量列表一次只允许一条在飞；手动刷新若撞上在飞的静默巡检，只借用它的收尾停转圈，
   不再叠第二条 300KB。

**预期效果**：会话在跑时的常驻下行从 ~150 KB/s 降到 **~20 KB/s**（只剩 SSE 增量 + 10s 一次的安全网），
流式正文不再被自己的快照请求挤掉；任何一次失败都会在 30s 内表现为可见的错误，而不是无尽的加载圈。

## [1.7.51] - 2026-10-29

### 🗂 项目列表改长按拖动排序 + MCP 面板的说明收敥成一个 ⓘ

**1) 拖动排序（接上 1.7.50 的「排序」行）**

- 工作区项目列表现在支持**长按拖动**排序：长按列表项开始拖动，位移超过相邻行一半高度就调一次相邻移动。
- 与菜单里的 `↑` / `↓` 走**同一条单步相邻移动**（`ProjectMovePlanner.plan` + `collectAddKeys`），
  因此提交给 pi-web `ui-state` 的 `projectKey / anchorKey / position / add` 仍然合法，
  不会重演 v1.7.48 修掉的 `400 projectKey and anchorKey must differ`。
- 手势仲裁：长按**松手时若没有真正拖动过**，仍按旧行为弹出项目菜单，所以菜单入口没有丢掉；
  同一行上 `combinedClickable` 只留 `onClick`，长按分支全交给拖动识别器，两者不抢事件。
- 拖起的那一行会抬高一级（`zIndex` + 高亮底色 + 跟随手指平移），松手归位。
- 菜单里的 `↑` / `↓` 保留：拖动不是无障碍可达的，箭头是键盘/读屏与「只挪一格」的精确退路。

**2) MCP 服务器详情：4 条 subtitle → 0 条 + 1 个 ⓘ**

- 删掉 `工具曝光数` / `启用此服务器` / `移除服务器` / `脚本模式` 四处副标题，
  三条副作用说明（裁切口径、关闭即隐藏、只删手机端注册表）统一收进头部新增的 `ⓘ`。
  理由：这四条本来散在「能力」「控制」两个分组里，同一屏三种解释形态并存，
  违反 `docs/DESIGN-labels.md` §5.3「形态唯一」。
- 设置页 `subtitle` 总数 28 → **13**（余下 5 处是数据/状态、6 处能对上判据）。

---

## [1.7.50] - 2026-10-29

### 🏷 说明性标签瘦身：项目操作面板 6 → 1，设置页 28 → 18，并定下使用边界

病根：`pi-web` 每个功能项都挂一行说明，但那是 ≥1200px 桌面宽下吃**右侧横向余量**。
手机把同一句话挤到标题下方，等于用**纵向空间**还这笔债——每多一行 subtitle 约 +17dp，
10 项菜单就是 +170dp ≈ 半屏。说明文字因此从「补丁」变成了默认写法。

**判据**：说明必须回答「不写会怎样」——只有会造成不可逆损失 / 方向性错误 / 误以为失败，
或它本身就是**数据/状态**时才允许留；复述标题、状态重复、操作步骤、氛围描述一律删。

- **项目操作面板（长按工作区项目）**：
  - 删掉置顶 / 重命名 / 新会话 / 归档 / 查看已归档的 5 条说明（标题已经说完了）；
  - 只留删除项那句「正在运行的会话会自动保留」（唯一的不可逆损失）；
  - **你吐槽的两个按键改掉了**：`列表排序` 标签 + 两个带边框的文字胶囊 → `排序` 标签 + 两枚**裸图标**
    （`↑` / `↓`，无边框无底色无文字，首末位置降到 45% 透明度）。
    排序是低频工具动作，不应与「置顶 / 重命名」同一声量。
  - 「重命名项目名称…」→ `重命名项目…`。
- **设置页**：`subtitle` 28 → 18。删掉的全是复述型（「任务完成时播放一次提示音」）、
  状态重复型（「完成时额外弹一张通知卡片」）、操作步骤型（「点插件行进入编辑弹窗」）；
  `主题外观` 从 subtitle 改成右侧的 `value`（那是数据，不是解释）；
  剩下的 12 条逐条过判据，并压到 ≤ 18 个汉字、1 行。
- **立规矩**：新增 `docs/DESIGN-labels.md`（判据 / 三种表达 / 文案规范 / 硬约束 / 验收指标），
  并在 `AGENTS.md §3.0` 写入三条硬约束：一项一行、一屏一说明、形态唯一。
- 效果：项目操作面板常驻说明 6 → **1** 条，且整块在一屏内读完；
  设置页每屏常驻说明回到 1–2 条。

---

## [1.7.49] - 2026-10-29

### ✨ 生成式 UI（`show_widget`）落地：微组件不再当工具卡裸奔

接入 `pi-generative-ui` 微组件链路，与桌面端 pi 的容器语义对齐、与 pi-web 无先例可据的部分按
`docs/DESIGN-artifact-viewer.md` 的既定口径裁量。

- **之前的样子**：模型调 `show_widget` 后，手机端把它当普通工具：`widget_code`（常 5~50KB 的
  HTML 片段）被 `formatToolInput` 序列化成一整块 JSON，塞进工具卡的 `command` 代码块。
  一屏只剩一段看不懂的标签，真东西根本渲染不出来。
- **现在**：
  - 聊天流里只留一张**单行入口卡**（标题 + 尺寸或「生成中…」+ 全屏图标），不铺预览、不加副标题；
  - 点开走**独立全屏查看页**（`Scaffold` + `TopAppBar` + 返回键），WebView 全屏渲染；
  - 表头右侧常驻 `宽×高`，方便判断微组件原本的桌面尺寸。
- **为什么不是聊天气泡内嵌 WebView**：WebView 不参与 Compose 的 nested scroll，
  嵌进 `LazyColumn` 会抢走整列滚动手势（与 1.7.43 把文件预览从半屏 sheet 改成全屏页同因）。
- **插件内部步骤隐藏**：`visualize_read_me`（加载设计指南）不再在聊天流里占一行 ——
  插件本身就要求这一步对用户不可见。
- **入参双路径**：历史回放从 assistant message 的 `input` 摊平成 `fields`；
  实时 SSE 只带 `{toolCallId, toolName}`、不带入参，所以再补一条「`command` 能解析成 JSON 就用」的兼顾，
  避免刚生成的微组件必须重进会话才出现。
- **安全口径**：开启 JS（微组件依赖 Canvas / Chart.js），但**不注入任何 JS 桥**、
  关闭文件与 content 访问、页面内跳转一律交给系统浏览器。
- 新增纯 JVM 单测 `GenerativeUiTest`：工具名识别、缺 `widget_code` 返回 null、
  title 的 snake_case 转空格、尺寸缺省与越界夹紧、SVG 判定、HTML 外壳、JSON 兼顾路径。

---

## [1.7.48] - 2026-10-29

### 🐛 修复「项目排序同步失败：400 projectKey and anchorKey must differ」

工作区项目菜单里的 `↑ 上移` / `↓ 下移` 一直报：

```
项目排序同步失败: 同步到 PC 失败 (400): {"error":"projectKey and anchorKey must differ"}
```

根因有三层，都在 `move-project` 这条链路上（pi-web 的 `ui-state` 接口是唯一真源）：

- **锚点取成了自己**（`SessionListScreen`）：旧实现先在本地列表里 `swap` 换位，
  再用 `currentList[idx - 1]` / `currentList[idx + 1]` 当锚点 —— 换位之后这两个位置
  正是被移动项目本身，于是 `anchorKey == projectKey`，服务端按校验直接 400。
  → 改为先按当前展示顺序算出锚点再换位，逻辑逐行对齐 pi-web `SessionSidebar` 的
  `moveAnchor()`：同分组（置顶 / 非置顶）内的「移动前」相邻项才是合法锚点。
- **缺少 `position` 字段**（`PiApiClient.moveProject`）：服务端只接受
  `"before"` / `"after"`，旧请求只有 `projectKey` + `anchorKey`，即使锚点正确也会
  400 `position must be "before" or "after"`。
  → 上移发 `before`、下移发 `after`。
- **缺少 `add` 字段**：pi-web 每次移动都会把「同分组内尚未进入 `projectOrder`」的
  项目 key 一并提交，用来固定 PC 端当前展示顺序；只写两个 key 会让未排序项目在 PC 上
  跳到列表末尾。→ 按 `unorderedKeysByBand` 的语义补齐（上限 500）。

附带修正：移动成功后回读一次 `ui-state`，让手机抽屉里的顺序立刻与 PC 一致
（排序真源在 PC，本地 `SharedPreferences` 只做镜像）；首/末项目仍自动禁用
对应按钮，跨分组不再发送非法请求。

新增 `ProjectMovePlannerTest`（10 例）锁定根因：锚点永不等于自身、
`position` 只能是 `before`/`after`、分组首末不可移动、`add` 只携带未排序 key。

## [1.7.47] - 2026-10-29

### 📐 修复工作区项目操作面板：底部被裁、排序按钮抢眼、危险操作无隔离

长按工作区项目弹出的维护面板在 10 项操作的规模下暴露了四个排版问题：

- **底部操作被裁掉**：面板内容是一个普通 `Column`，没有任何滚动容器，而 `ModalBottomSheet`
  默认停在「部分展开」高度。`删除全部会话` 的副标题直接被拖出可交互区域，既看不到也读不到。
  → 内容列加 `verticalScroll`，并把 sheet 改成 `skipPartiallyExpanded = true`（默认全展开）。
  内容短时仍然自适应高度，内容长时可滚动读完。
- **排序按钮权重过高**：「上移项目 / 下移项目」曾是两个几乎等宽、等高的行级大按钮，
  而这是低频次要操作，与「置顶 / 重命名」等主操作平级抢注意力；而且排在首尾时点了没反应。
  → 改成右侧一对紧凑 chip（`↑ 上移` / `↓ 下移`），左侧留 `列表排序` 小标签说明用途，
  并根据当前位置自动禁用：首个项目不能再上移、末个不能再下移。
- **危险操作紧贴普通操作**：红色 `删除该项目全部会话` 原本直接跟在 `查看已归档会话` 下面，
  同一条视觉流，误触成本高。
  → 前面补独立分隔线 + 间距，并改用淡红底 + 红色描边容器把它单独圈出来。
- **禁用态看不出来**：禁用项背景用的是 `surfaceContainerLow.copy(0.5)`，而 sheet 底色恰好也是
  `surfaceContainerLow`，两者几乎无差异。
  → 改用深一档的 `surfaceContainer` + 整行 `alpha(0.55)`，禁用与可用一目了然。

顺带：副标题 `maxLines` 从 1 放宽到 2（中文说明在窄屏下 1 行必被截成半句）；
顶部拖拽手柄换成自绘细条，去掉 `BottomSheetDefaults.DragHandle` 自带的 22dp 上下内边距，
标题上方的多余空白一并圧掉。

---

## [1.7.46] - 2026-10-29

### 🔍 修复：搜索结果的正文命中区滑不动、还会误开工作区抽屉

- **现象**：在主页搜索会话时，上方「正文命中」一多，下面的会话列表就顶出屏幕，整块区域
  既滑不动也点不下去；手指在这个区域反复上下滑，还会把左侧工作区抽屉拉出来。
- **真因一（布局）**：正文命中卡片当时被直接渲染在 Scaffold 内容 Column 的静态头部里
  （`searchHits.take(8)` 的 `forEach`），而下方会话列表用的是 `Modifier.fillMaxSize()`。
  `fillMaxSize()` 在 Column 里取的是**入参最大高度**而不是剩余高度，所以头部越高、列表被推得越远，
  最终只剩屏幕外的一截。列表本身是可滚动的，但用户碰不到它。
- **真因二（手势）**：静态头部不消费垂直拖拽。`ModalNavigationDrawer` 的 `gesturesEnabled`
  在整个内容区做横向拖拽识别，垂直拖拽没人消费时，手指略斜就会被它判成「拉开抽屉」。
  两个症状其实是同一个根因的两个面。
- **修复**：
  - 正文命中抽成 `SessionSearchHitsCard`，改为会话列表 `LazyColumn` 的**首个 item**，
    与列表一起滚动；显示上限从 8 条放宽到 20 条（滚得动了，多给才有意义）；
  - 会话列表 / 加载态 / 空态三处 `fillMaxSize()` 全部改成 `fillMaxWidth().weight(1f)`，
    列表永远拿到剩余高度 → 垂直拖拽有人消费，抽屉不再抢手势；
  - 命中存在但无标题匹配时，列表内给一行「没有标题匹配的会话，正文命中见上方」，
    不再整屏只剩空态；
  - 搜索态（`searchQuery` 非空）禁用抽屉手势，改用左上角菜单按钮打开，避免边打字边误开。

---

## [1.7.45] - 2026-10-29

### 🧩 设置页收尾：MCP / 技能 / 插件 三页统一到「分组胶囊」

上一版把系统设置与模型清单改成分组行之后，同一个屏幕里 MCP、技能、插件三个 Tab 还是
横幅 + 一人一张卡的老结构，描边粗细（0.7dp / 1dp alpha）与按钮语汇继续各行其是。
本次三页一次性收敛，只保留一种卡容器与一条行高规则。

- **MCP 协议**：拆成「列表态 + 服务器详情」两级。
  - 列表态：执行模式胶囊（自动 / 始终 / 关闭）+ 脚本模式开关 + Token 预算（右对齐等宽值）合成一组；
    服务器变成单行（状态点 + 名称 + `http · 6 个工具 · 已连接` 等宽元信息 + `›`），
    不再一人一张 120dp 高、内嵌测试/编辑/移除三个按钮的卡片。
  - 详情态：状态 / 传输方式 / 端点（可复制）/ 作用域 / 工具曝光数 / 测试 / 启用 / 编辑 / 移除
    全部单行；返回键与左箭头都能退回列表（Tab 内 `BackHandler`，不会误退出设置页）。
  - 测试结果仍在弹窗里给工具清单，但触发点从卡片底部按钮变成「测试连接」行的行内动作。
- **技能中心**：项目 / 全局技能各收成一张分组卡，行内不再各自描边；
  「发现 N 项技能可升级」横幅删除，改为行内琥珀色 `更新` 值（点它就升级）；
  空分组不再输出空卡片。
- **插件扩展**：安装 / 更新 / 卸载三处散落按钮收敛为——
  行内 `更新` + 开关、点行进编辑弹窗（弹窗左下角新增红色「卸载」），
  底部"说明"组解释启停生效时机；`v2.4.1 · 全局 · npm:context-mode` 合成一行等宽副标题；
  新增搜索（按包名或源地址）。
- `SettingsRow` 新增 `leading`（前置状态点）与 `monoSubtitle`（等宽副标题）两个可选参数。

---

## [1.7.44] - 2026-10-29

### 🎛️ 全局设置改版：系统设置 / 模型管理套用「分组胶囊」设计语言

- **背景**：技能中心、插件扩展两页在上一轮已经收敛成「单行卡 + 弱色副标题 + 单一控件」的密度，
  但系统设置仍是一张 10dp 圆角 Surface 里塞主题选择器 + 触觉 + 音效，模型管理则是横幅/按钮排/chips
  层层堆叠——同一屏里出现三套描边粗细、两套分隔线 alpha，层级读不出来。
- **统一组件**：新增 `ui/settings/SettingsComponents.kt`（`SettingsGroup` / `SettingsRow` /
  `SettingsSwitch` / `SettingsChip` / `SettingsInlineAction` / `SettingsSearchField` /
  `SettingsGroupActionRow`）。一屏只用一种描边：分组卡 1dp `outline`，组内分隔线同色同粗细，
  彻底停用 `0.5f / 1f` 混用的分隔线。
- **系统设置**：拆成 外观与反馈 / 后台与保活 / 会话行为 / 网关连接 / 关于与升级 五组，
  组标题降为 11sp 弱色，每行最多一个主控件（Switch 或 chevron），状态一律右对齐成值文本：
  - 主题选择从「当前主题: xx (深色)」自述文字改为右侧 5 个可点击色点，选中项加描边环；
  - 「试振 / 试听」从独立按钮降级为行内次级文字动作，不再和 Switch 抢右侧空间；
  - 电池优化白名单：未加入时值显示「去开启 ›」，加入后转绿显示「已加入」；
  - 访问令牌默认打码，行内保留「显示 / 复制」；
  - 「关于与升级」独立成组（版本号、检查更新、第三方镜像 + 说明、开发者、许可），
    不再与会话开关共处一卡。
- **模型管理**：模型清单改为单行行式 + 本地搜索：
  - 新增搜索框（按模型 ID / 名称即时过滤），30+ 个模型时不必再靠滚动找；
  - 每行 = 选中圆点 + 等宽模型 ID + 「名称 · 思考 · 视觉」副标题 + 右对齐 `ctx / max` 等宽数值，
    去掉每行卡片描边；切换提供商时自动清空搜索词。
- 无数据层改动：所有回调签名（`onSetDefaultModel` / `onToggleHaptics` / `onModelsConfigChanged` …）
  保持不变，`MainActivity` 调用点无需改动。

---

## [1.7.43] - 2026-10-29

### 🪟 文件查看器改全屏：不再「一上滑就缩回半屏」

- **现象**：点开 md 文件后底部弹出半屏 ModalBottomSheet，想看正文就得往上滑，
  弹窗变大到几乎整屏，手指一停又**缩回半屏**；读到末尾继续下拉还会**误关闭**。
- **真因**：`LazyColumn` 与 `ModalBottomSheet` 各自装着一个 nested-scroll connection，
  fling 速度会串到 sheet 自己的拖拽手势上（AOSP issue 353304855 同一现象）；
  docx / SVG 预览是 WebView，根本不参与 Compose 的 nested scroll，手势必被外层抢走。
- **修复**：新增 `ArtifactViewerFullScreen`（`Scaffold` + `TopAppBar`）全屏承载
  文本 / md / SVG / 图片 / Word / PDF / 音视频，彻底去掉可拖拽容器：
  - 关闭：系统返回键、系统手势返回、顶栏返回按钮（48dp）；查看器内部自带 `BackHandler`，
    不会漏到外层「退回会话列表」；
  - 沉浸：点击正文切换顶栏显隐，返回按钮不会常驻隐藏；
  - 动作收口到顶栏 `⋮`：复制全部 / 复制文件路径 / 用其他应用打开 / 重新加载；
    「预览 ÷ 源码」保留为正文上方的模式开关；「加载更多」保持行内（与 pi-web 1:1）；
  - **错误态不再只有一行红字**：主操作「重新加载」+ 次操作「用其他应用打开」+
    可长按选中的路径（上一次 403 事故若有它，一分钟就能定位）。
- `AndroidManifest.xml` 补上 `android:enableOnBackInvokedCallback="true"`，
  Android 13/14 上也能拿到 predictive back 预览动画（app 内无 `onBackPressed()` 覆盖，无回归风险）。
- 「手机全屏 / 平板分栏」的偏离说明与后续计划见 `docs/DESIGN-artifact-viewer.md`。

---

## [1.7.42] - 2026-10-29

### 🐞 修复「点开文件 403 Access denied」：Git Bash 形态路径 `/d/...` 还原成 `D:/...`

- **现象**：会话里点开报告链接报 `读取文件失败：Access denied`，md 不渲染（只有错误行）；
  点「用其他应用打开」报 `下载失败：下载失败 (403)`。
- **真因（curl 实测同一台机器）**：pi 在 Windows 上常把路径写成 Git Bash / MSYS 形态
  `/d/myscript/eldercare-agent-platform/docs/...`。App 把 href 当「POSIX 绝对路径」原样塞进
  `GET /api/files/<path>?type=read|download`，pi-web 的允许根检查（会话 cwd / projectRoot）
  自然判定它不在任何工作目录内 → `403 {"error":"Access denied"}`。
  同一路径换成 `D:/myscript/...` 立即 200，与业务、权限、隧道无关。
- **修复一**：`FileLinks.normalizeMsysDrivePath()` —— 当基准目录（会话 cwd / 当前文档目录）
  本身是 Windows 路径时，把首段为单盘符字母的 `/d/x` 还原成 `D:/x`（盘符取自链接本身，
  所以 `/c/Users/...` 仍指向 C 盘）。Linux 网关上的真 POSIX 路径不受影响。
  该规则同时作用于 markdown 链接、工具卡片里的文件路径、以及预览文档内的相对链接。
- **修复二**：所有 `/api/files` 请求补上 `?sessionId=<当前会话>`，与 pi-web FileViewer 的
  `sourceSessionId` 对齐 —— 允许根之外的「本会话引用过的文件」（临时产物、`~/.pi` 下的文件）
  现在也能打开，而不是一律 403。
- 回归测试：`app/src/test/.../FileLinksMsysPathTest.kt`（5 组：盘符还原、盘符取自链接、
  Linux 基线不动、非盘符形态不动、Windows 基准目录识别）。

---

## [1.7.41] - 2026-10-29

### 🎨 技能中心排版重构：入口三合一 + 卡片压扁（首屏 1.5 项 → 6 项）

- **入口三合一**：原来「添加技能」出现三处（顶部 pill、标题行按钮、列表底部大按钮），
  「已安装技能 (12)」与标题「技能管理中心 (12 项)」计数还重复了一遍。
  现合并为顶部一行固定工具区：`搜索已安装技能` + `+`（唯一添加入口）+ `↻`（检查更新）。
- **卡片压扁（~110dp → ~58dp）**：删掉每张卡都重复的第三行
  `🟢 模型可见（可由模型主动调用）`——可见性已由左侧状态点、右侧开关、整卡明暗三重表达，
  该说明移入筛选行末尾的 `ⓘ` 弹窗（同时说明 `.pi/skills` 与 `~/.pi/agent/skills` 的差异）。
  描述改为单行，全文进详情页；`别名 ✎` 仅在已设置别名时显示，长按卡片可改别名。
- **分组轻量化**：`项目技能 / 全局技能` 分组头去掉卡片边框，改为「图标 + 标题 + 计数 + 批量开关」标签行；
  空分组折叠成一行「暂无项目技能 · 去添加 →」；有内容的组优先显示，
  12 项全局技能不再被 0 项项目组挤到第二屏。
- **新增本地筛选**：`全部 / 可见 / 隐藏 / 可更新` 四枚等宽 chips + 即时本地过滤，
  与原 skills.sh 远程搜索完全隔离（不发网络请求）。
- 修掉一个布局陷阱：`Column` 内的列表改用 `weight(1f)`，避免取整屏高度导致底部被裁。
- 预期收益：12 项技能从 3~4 屏降到约 1.5 屏，垂直空间净省约 380dp。

---

## [1.7.40] - 2026-10-29

### 🧭 修复「更新后右侧时间轴消失」：窗口里没有提问行就自动补页 (Release v1.7.40)

- **现象**：更新后打开某些历史会话，右边整条时间轴导轨不见了（正文照常显示）。
- **真因（实测本机数据）**：首屏取的是 `GET /api/sessions/{id}?tail=60` 的**最近 60 条 entry**。
  工具调用密集的会话里，这 60 条可以全是 `assistant` / `toolResult`，**一条 `user` 都没有**：
  ```
  01a18427  msgs=164  user=0  hasMore=true
  01a168f7  msgs=119  user=0  hasMore=true
  ```
  而时间轴的节点是「每次提问」，`allTurns` 由 `MessageRole.USER` 构建 —— 没有提问行就成了空列表，
  `if (allTurns.isNotEmpty())` 整块不渲染，于是看起来像"时间轴被删了"。
- **修复**：快照回来后若 `messages` 里没有任何提问行、而服务端 `hasMore=true`，
  自动向前补页（最多 4 页、静默、不弹 Toast、不盖加载态），把最近的提问拉进窗口；
  每个会话只尝试一次，避免每轮同步都狂拉。重新进入该会话会再给一次机会。
- 补页成功后时间轴与「上滑加载更早的 N 轮」一起恢复正常。

---

## [1.7.39] - 2026-10-29

### 🧭 时间轴高亮对齐 pi-web：点哪个亮哪个 (Release v1.7.39)

- **现象**：点右边时间轴第 N 个小方块，深蓝色（选中态）却亮在 **N-1**。
- **对照 pi-web 源码**（`ChatMinimap`，逐条照抄它的口径）：
  1. **点击锁**：`W(index)` 先把目标设为高亮，并写 `lock = {index, until: now+1600}`；
     之后 1.6s 内的滚动事件**直接返回锁定的 index**，不重算；
  2. **滚动取点**：`anchor = scrollTop + 0.3 * clientHeight`，在所有回合起点里取**离锚点最近**的那个，
     距离相同取靠前的（`reduce` 里的严格小于）；长回合内部则保持原高亮；
  3. **跳转位置**：`scrollTo(turnTop - 0.3 * clientHeight)` —— 让目标回合顶部落在视口 **30%** 处，而不是贴顶。
- **App 侧根因**：我们既没有点击锁，取点用的还是「第一个可见行」，而且跳转是贴顶滚动。
  跳转那一帧布局未稳时，上一行的尾巴还在视口里，于是被算成「当前回合」→ 高亮退回一格。
- **修复**：
  - `activeTurnNumber` 改为「可见的回合起始行（`turn_N_user_*`）里离视口 30% 锚点最近者」，
    过程行/答复行不再参与取点（避免长回合内高亮乱跳）；
  - 点击后 **1.6s 锁**，期间的滚动不覆盖高亮；
  - 跳转改为 `scrollToItem(target, -0.3 * viewportHeight)`，目标回合顶部落在 30% 处，与锚点重合。

---

## [1.7.38] - 2026-10-29

### 🔧 修复 Release 构建：分支回退重置处的 `List.clear()` 编译错误

- **`MainActivity.kt` 的 `replaceHistory`（分支回退导航）用 `olderMessages.clear()` 重置翻页缓存**，但 `olderMessages` 是不可变 `List` 的 state（`var olderMessages by remember { mutableStateOf<List<ChatItem>>(emptyList()) }`），`clear()` 在编译期即失败：
  - 自 v1.7.35 引入以来，GitHub Actions 的 `Build & Release piDroid APK` 一直以 `e: MainActivity.kt:835:43 Unresolved reference 'clear'` 失败（v1.7.35 / 1.7.36 / 1.7.37 三连失败均为此一处）；
  - 改为整体赋值 `olderMessages = emptyList()`，语义不变（重置已翻页的更早记录，让游标逻辑重新从服务端取），并与 `messages.clear()`（`SnapshotStateList`，合法）区分开。

## [1.7.37] - 2026-10-29

### 🩹 修复过程输出成段重复：SSE 在途消息身份 + 心跳活性判定

- **整条 message 快照改为「覆盖同一条消息」而不是追加新气泡**（`StreamingMessageMerge.kt` 新增 + `MainActivity.kt`）：
  - 现场形状固定为 `正文 → 工具卡 → 同一段正文`：pi 每次 SSE 连上都会补发 `message_start{message: streamingMessage}`（整条快照），而旧实现只在「列表最后一条是 AGENT」时才覆盖，尾部是工具卡时走进「新开气泡」分支，于是同一段正文被重复渲染，并被 `inFlightTail` 保活而越攒越多；
  - 新增纯 JVM 的 `StreamingMessageMerge`：用「在途消息身份 `ChatItem.streamMessageId` + 本轮作用域（最后一条 USER 之后）」定位落点，同一条消息的正文永远写回同一条气泡，重复补发判定为 `noop`（天然幂等）；
  - `ChatItem` 增加 `streamMessageId`；`SessionSnapshotMerge.mergeItem` 在被服务端快照覆盖时把本地在途身份带过去，避免一次对账就抹掉身份、下次重连又新开重复气泡。
- **pi 的 30s 心跳此前被当成断流**（`PiSseClient.kt` + `MainActivity.kt`）：
  - `heartbeat` 事件以前直接返回空列表，`lastSseActivityAt` 不会刷新；而「流静默 15s 就重挂」的健康检查把每两次心跳之间的空白判成断流 —— 每重挂一次，服务端就补发一份快照，正是重复的放大器；
  - 现在心跳上抛为事件并刷新活跃时间，静默阈值由 15s 放宽到 45s（> 心跳周期 30s），`connected` 也即时刷新活跃时间。
- **消息级事件带上身份**（`PiSseClient.kt`）：
  - `message_start` 改为上抛 `message_snapshot`（含 `messageId`，优先 `id/responseId/messageId`，回落到 `timestamp`），即使正文为空也上抛，让上层先记住在途身份；
  - `message_end` 时清空在途身份并把属于该消息的气泡统一收尾（可能已不在列表尾部）；`agent_end` 解析 `aborted` 字段并作废在途身份；切换会话、发起新一轮时同样作废，防止跨会话认领。
- **完成提示音 1:1 对齐 pi-web 包络**（`PiSoundSynthesizer.kt`）：指数衰减由固定 `-5.5` 改为按 `exponentialRampToValueAtTime(0.001, s + 0.45)` 反解的 `-12.07664`，尾音按 pi-web 的 0.001 收敛而不是提前消失。
- **测试**：新增 `StreamingMessageMergeTest`（11 例，覆盖重连快照幂等、工具卡夹在中间的续写、跨消息/跨轮不串写、无身份时的尾部语义），并修正其中一处笔误的下标断言（两元素列表尾部为 AGENT 时应为 `size-1 = 1`）；与 `SessionTreeNavigationTest` 合计 20 例本地 JVM 全绿。

## [1.7.36] - 2026-10-29

### ✏️ 补齐 pi-web 编辑态两项：源消息气泡高亮 + 附件随草稿一起恢复

- **编辑目标消息高亮（对标 pi-web `MessageView` 的 `isEditing`）**（`ChatScreen.kt`）：
  - 被「从此处编辑」选中的那条提问气泡本身会进入编辑视觉态：底色混入 14% 主题色、边框换成 62% 主题色、外侧再加一圈 2dp 的 16% 主题色光环（完全复刻 pi-web 的 `color-mix` + `box-shadow`）；
  - 该消息底部工具条把「从此处编辑」换成主题色的「取消」按钮（pi-web 的 `canEdit && !canCancelEdit` + `canCancelEdit` 分支），点击即退出编辑态；
  - 编辑态同时覆盖首屏前置消息区与每个回合的用户提问，条目匹配以 `entryId` 为准（不是文本匹配）。
- **回填与恢复都带上附件（对标 pi-web `replaceMessage` / `restoreSubmission`）**（`ChatScreen.kt`）：
  - `ChatAttachment` 扩展为「本地 Uri」或「内联 base64」两种来源；`encodePickedImages` / `readPickedFiles` 同时支持两者；
  - **进入编辑态**：复刻 pi-web `getUserMessageDraftImages(message)` —— 该条提问当时附带的图片会一并回填进输入框附件区（`mediaList` 的底图直接复用，不需要重新读盘）；
  - **发送被拒时恢复**：复刻 pi-web `restoreSubmission(text, images)` —— 除正文与编辑态外，本次提交的附件也原样退回，不再只恢复文字；
  - 复刻 pi-web `canRestoreUserMessage` 守卫：输入框已有正文或附件时**不覆盖用户草稿**，只武装编辑态并提示「未覆盖你的草稿」。

## [1.7.35] - 2026-10-29

### 🔬 分支回退链路代码审查复核：补齐 4 处与 pi-web 不一致的遗漏

- **权威历史替换（`replaceHistory`）——修复「旧过程输出有时不消失」的根因**（`MainActivity.kt`）：
  - 复查发现 v1.7.33 的截断仍走增量合并路径，会被 `isAgentBusy` 残留、`inFlightTail`（流式尾部保留）等分支绕过，导致「停止后立刻编辑」时旧的工具输出仍可能被拼回列表；
  - 现完全复刻 pi-web `loadSession` 的语义：分支切换成功后**整段替换**本地 `messages` 为服务端活跃分支，同时重置已翻页的 `olderMessages` 与翻页游标，杜绝重叠与残留。
- **分支失败时恢复草稿与编辑态**（`ChatScreen.kt` + `MainActivity.kt`）：
  - 复刻 pi-web 的 `restoreSubmission + setEdit(entryId)`：`navigate_tree` 失败（服务端仍在执行、条目已不存在）或本地判定任务执行中时，自动把正文退还输入框并重新进入编辑态，不再出现「发送后正文丢失」；
  - 发送入口新增 `onEditRejected` 回调签名，输入框不再无条件清空后无法挽回。
- **消息级「新会话」真正的分叉语义**（`PiApiClient.kt` + `MainActivity.kt`）：
  - 复查发现该按钮此前忽略了被点击的消息，直接调用 `/api/sessions/{id}/fork`（整条当前分支 Clone），与 pi-web 的 `handleFork` 语义不符；
  - 新增 `forkAtEntry()` 走 `POST /api/agent/{id}` `{type:"fork", entryId}`，以该条提问为切点新建独立 `.jsonl`，源会话不受影响（可边跑边分叉）；`entryId` 缺失时明确提示而非静默克隆。
- **代码整洁与可测性**（`SessionSnapshotMerge.kt` + 测试）：
  - 把快照对账规则（`isSameChatContent` / `mergeItem`）从 Activity 内联函数抽成纯 JVM 对象 `SessionSnapshotMerge`，`MainActivity` 仅做编排；
  - 重写 `SessionTreeNavigationTest`，改为测试**生产代码本体**（9 个用例，覆盖 entryIds 平行数组绑定/长度不匹配容错、乐观消息 entryId 补齐、根节点回退清空、第二轮回退保留第一轮、权威替换丢弃流式尾部、运行中增量同步保留尾部），本地 JVM 全部通过；
  - 移除 `ChatScreen` 中已失效的 `onEditFromMessage` 回调参数与重复 Toast。

## [1.7.34] - 2026-10-29

### 🛠️ 修复分支编辑状态判定并发布正式 Release（Release v1.7.34）

- **修复编译与分支编辑执行态判定**（`ChatScreen.kt`）：
  - 修复 `ChatScreen` 内部错误引用未在参数表声明的 `isToolExecuting` 变量导致的 CI 编译错误；
  - 严格以 `isAgentBusy` 作为任务执行态拦截标准，在 Agent 运行时阻止回退分支，避免并发状态冲突。

## [1.7.33] - 2026-10-29

### 🌲 会话树分支回退与历史消息编辑体验 1:1 对齐 pi-web (Edit from here)

- **1:1 复刻 pi-web 会话树导航与分支回退闭环（In-session Branching）**：
  - **延迟切分支策略（Delayed Branch Switch）**：点击用户提问底部的「从此处编辑」时，严格遵循 pi-web 行为仅预填输入框并记录待编辑目标，绝不提前调用接口切除历史；用户取消编辑或刷新页面绝不丢弃后续会话；
  - **发送前原子树导航（navigate_tree on Send）**：用户在输入框修改内容点击「发送」时，前置触发 `navigate_tree` 指令回退会话树 Leaf，服务端自动将活跃分支指针移动至被编辑消息的父节点；
  - **权威快照截断对齐**：分支切换成功后即刻全量同步权威快照，客户端界面瞬间彻底清除该提问之后所有的历史、工具调用与中途停止产生的中断过程输出，旧输出不再滞留或串入新分支；
  - **编辑态 UI 状态提示**：输入框上方新增专属 Edit Mode 指示横条（显示“从历史提问编辑分支”与快捷“取消”按钮），视觉与交互彻底消除歧义。
- **底层条目 ID（entryId）解析与对账彻底修复**（`PiApiClient.kt`）：
  - 根因修复：解决服务端 `/api/sessions/[id]` 返回中消息条目位于 `context.messages` 而条目 ID 位于平行数组 `context.entryIds`，导致历史消息解析出的 `entryId` 全面丢失为 `null` 的重大 Bug；
  - 建立 `initialCtxMsgs`、分页上下文 `olderMsgs` 与 `entryIds` 的严格索引配对绑定，并在 `extractChronologicalMessagesFromEntries` 中正确保留 `entry.id`；
  - **本地乐观消息静默补全**：在快照比对合并中，当服务端快照落盘并给出 `entryId` 时，自动无感为本地刚发送的消息补全 `entryId`。
- **停止执行（Abort）态即刻对账**（`MainActivity.kt`）：
  - 用户点击停止 Agent 执行（`onStopAgent`）成功后，立即触发一次权威快照同步，确保停止态中断记录与分配好的 `entryId` 瞬间全部同步到本地内存。
- **单元测试覆盖**：新增 `SessionTreeNavigationTest.kt` 纯 JVM 单元测试，全面覆盖消息条目 ID 绑定、乐观条目补全与分支回退截断逻辑。

## [1.7.32] - 2026-10-28

### 🔍 移动端 Git Diff 体验专项重构（深度对标 GitHub Mobile 规范）

- **彻底根治 Compose 0 宽度测量导致的白屏 Bug**（`StructuredDiffView.kt`）：
  - 根因排查：在单行横向滚动模式（`!softWrap`）下，父容器通过 `horizontalScroll` 提供了无边界宽度（`maxWidth = Infinity`），而子项 `Text` 设置了 `weight(1f)`。在 Compose 的 `Row` 测量策略中，权重无法瓜分无限宽度，退化为测量为 `0` 宽度，导致整行代码文字直接消失变空白，只有手动切到「已自动换行」才强行绕过；
  - 核心修复：在单行滚动模式下解绑 `weight(1f)`，使用自然宽度测绘，消除文字凭空消失的 Bug；
  - 视口全宽底色加固：引入 `BoxWithConstraints` 保证短行在横向滚动时的绿/红高亮底色能够撑满整个视口，不会在中间突兀截断。
- **移动端优先：默认开启自动换行与偏好持久化**（`GitChangesFullScreen.kt` + `AppPreferences.kt`）：
  - 对标 GitHub 官方安卓客户端设计哲学，手机竖屏场景（宽度仅 360~412dp）默认启用「自动换行」，点开即读，单手即可流畅自上而下审阅，免去左右反复频繁横滑；
  - 新增 `prefs.gitDiffSoftWrap` 持久化，用户切换换行/单行滚动偏好后全局自动记忆，跨文件与重启均无需重复点击。
- **Gutter 瘦身与屏幕有效面积大幅提升**：
  - 行号槽位（旧行号 + 新行号 + 符号）总宽由原先的 89dp（侵占近 25% 屏幕面积）极限紧凑压缩至 ~66dp，为代码正文腾出 **15%+（约 5~8 个字符）** 的有效横向宽度；
  - 左侧 3dp 红绿指示条改用 `drawBehind` 绘制，杜绝 `fillMaxHeight` 在无界容器中高度丢失问题。
- **Hunk Header 通栏 Banner 独立分块**：
  - 淘汰旧版将 `@@ -a,b +c,d @@` 伪装成代码行挤在行号列（显示畸形 `... @ @@...`）的陈旧做法；
  - 升级为 GitHub Mobile 规范的**全宽独立通栏横条**，搭配柔和浅蓝底色、等宽药丸标签以及右侧淡化的上下文函数/变量名说明。
- **行内词级高亮（Intra-line Word Diff）**（`UnifiedDiffParser.kt`）：
  - 对紧邻的 1:1 删改行进行最长公共前后缀（Common Prefix/Suffix）剔除，精确定位被修改的具体词汇/参数；
  - 在整行浅绿/浅红柔和底色上，对被变动的具体词汇叠加深一度的高亮底色（`SpanStyle`），审阅配置与参数变动一目了然。
- **差异规模动态徽标**：
  - 详情页模式顶栏实时统计当前文件的变动行数并展示绿色 `+N` / 红色 `-N` 胶囊徽标。

## [1.7.31] - 2026-10-28

### 🌿 Git 工作目录代码变更独立全屏页与结构化 Diff（Release v1.7.31）

- **独立全屏变更与差异阅读页**（`GitChangesFullScreen.kt`）：
  - 彻底淘汰原先写死在 `ModalBottomSheet` 里的 420dp 半高弹层，消除手机竖屏阅读代码时的局促感与向下滑动误退冲突；
  - 采用清晰的两级全屏页面：`工作目录变更文件列表` <-> `单文件详情`，支持 Android 系统返回键逐层返回，并精准保留列表滚动位置；
  - 顶部统计展示增删统计 `CHANGES (N) +adds -dels`，文件列表按目录路径半透明淡化、文件名加粗清晰排版。
- **结构化 Unified Diff 渲染器**（`StructuredDiffView.kt` + `UnifiedDiffParser.kt`）：
  - 对标 pi-web `lib/patch.ts` 实现完整的 Unified Patch 解析，准确区分文件头部、`@@` 块头、增加行、删除行与上下文行；
  - 采用 GitHub / pi-web 风格的专业 Diff 视觉：浅绿/浅红柔和底色 + 左侧 3dp 高对比度绿/红指示条 + 精准双列行号；
  - 基于 Compose `LazyColumn` 按行惰性排版，支持百万级长补丁无卡顿滑动；
  - 提供单行横向自由滚动与「自动换行」一键切换，完美适配手机小屏幕阅读超长代码行。
- **详情页一站式多维查看（差异 / 源码 / 预览）**：
  - 详情页内部直接切换「差异」、「完整源码」与「可视化预览」（Markdown 文档与 SVG 矢量图），不再二次弹窗叠层；
  - 增加请求序列号防护机制，防止快速切换不同文件或频繁重试时异步结果乱序覆盖；
  - 精准区分并引导「加载中」、「网络错误重试」、「非 Git 仓库」、「工作目录干净」与「文件不支持 Diff（二进制/大文件）」。
- **顶栏交互净化**：
  - 会话顶栏移除置顶按钮，为会话标题与项目下拉框释放出珍贵宽度，彻底解决长标题被省略号过度截断的痛点；
  - 保持会话卡片三点菜单内的「置顶/取消置顶任务」与侧边栏的「置顶/取消置顶工作区项目」不变，职责更加清晰。

## [1.7.30] - 2026-10-28

### 🔗 消息里的 Markdown 链接可点了：复刻 pi-web 的「链接即文件预览」（Release v1.7.30）

**pi-web 源码依据**（`components/MarkdownBody.tsx` + `lib/file-links.ts`）：

```tsx
// MarkdownBody.tsx：<a> 不是跳转，命中本地文件就拦下来开预览
const filePath = onOpenFile ? resolveLocalFileHref(href, cwd) : null;
const handleClick = (event) => {
  if (!shouldOpenLocalFileInApp(event)) return;
  event.preventDefault();
  openFile(path, parsePdfPageFragment(href) ?? undefined);
};
```

```ts
// lib/file-links.ts：href → 本机绝对路径（相对路径以会话 cwd 为基准，禁止越界）
resolveLocalFileHref(href, baseDir, relativeRoot = baseDir)
```

也就是说 pi-web 里 `[远端上线报告](docs/S5-远程上线报告.md)` 点下去是 **右侧面板的文件预览**
（`.md` 走文档渲染、带 Markdown 正文与内部链接），而不是浏览器跳转。

**本地为何点了没反应**：`MarkdownRenderer` 的 `is Link` 分支只 `pushStyle`
（蓝字 + 下划线），一个字符的点击语义都没有；CommonMark AST 里 `Link.destination`
被读完就丢了。手机上又没有“浏览器能打开的 `file://`”，于是链接永远是一行好看的死字。

**修复**：
- 新增 `ui/components/FileLinks.kt`：`lib/file-links.ts` 的 Kotlin 移植
  （`file://` URL / Windows 盘符 / UNC / POSIX 绝对路径 / 相对路径按 cwd 拼接 /
  越界拒绝 / 剥离 `:12:3` 行列后缀 / `#page=` 解析）。
  移植后用 pi-web `lib/file-links.test.mjs` 的 **全部用例** 在 JVM 上回放，41 项全通过。
- `MarkdownRenderer` 新增 `onOpenLink`：链接内字符挂 `LinkAnnotation.Clickable`
  （Compose 1.7 的官方链接 API），回调沿 `MarkdownRenderer → MarkdownBlock →
  RenderAstBlockNode → buildInlineAnnotatedString → appendInlineChildren` 逐层透传，
  标题 / 正文 / 引用 / 列表 / 表格单元格里的链接全部生效。
- `ChatScreen`：`openLinkTarget(href, baseDir)` —— 本地文件 → 打开预览面板；
  `http(s)` → 系统浏览器；其余忽略。新增 `sessionCwd` 参数（`MainActivity` 传
  `currentSession.cwd`，缺失时回退 `projectRoot`）作为相对路径的基准目录。
- 预览面板补齐「文档形态」：`.md/.markdown/.mdx` 默认用 `MarkdownRenderer` 渲染而不是
  等宽文本（对标 pi-web FileViewer 的 `isMarkdown && preview` 分支），并提供「预览 / 源码」切换；
  文档内部的相对链接以**该文档所在目录**为基准（对标 `markdownDirectory`），越界时才回退工作目录。
- 长文件「加载更多」续读：`type=read` 一次只返回 256KB（pi-web `TEXT_PREVIEW_MAX_BYTES`）
  并带回 `nextOffset / truncated / size`，旧代码只读 `content`，于是超长报告被**静默截断**。
  现在按 pi-web `Load more` 的语义补上：底部显示 `已加载 / 总大小` + 「加载更多」按钮，
  用上一块的 `nextOffset` 续读并拼接；同时对齐 pi-web 的判断 —— **文档未加载完整时不给 Markdown 渲染**
  （半篇文档里未闭合的代码围栏、表格会渲染成错乱样子），加载完整后自动可切预览。
- PDF / Word / 音视频不再报误导性的「HTTP 200」：按 pi-web FileViewer 的分派补齐了各自的预览器（见下条）。

### 🎞 PDF / Word / 音频 / 视频预览（Release v1.7.30）

pi-web 的这四种「支持」其实来源不同，复刻方式也不同：

- **PDF**：`FileViewer.tsx` 只是 `<iframe src="/api/files/<path>?type=read#page=12">`——
  靠的是**桌面浏览器自带的 PDF 阅读器**，pi-web 自己不解码 PDF。手机端同理：
  把文件流式落到应用缓存，再用 **FileProvider + ACTION_VIEW 选择器** 交给 WPS / 系统 PDF 阅读器
  （可缩放、可搜文本、可批注）。与 `AppUpdateManager` 拉起安装器是同一套 FileProvider 机制。
- **Word（.docx）**：pi-web 在**服务端**用 mammoth 转 HTML（`?type=preview`，图片内联 data URI，
  上限 `DOCX_PREVIEW_MAX_BYTES = 10MB`），前端只是塞 iframe。手机端复用同一接口，
  拿 HTML 塞 `WebView`（关 JS / 关 file access，符合该响应的 CSP）。
- **音频**：pi-web 是浏览器 `<audio>`；手机端用 `MediaPlayer` 流式播放。
  **关键坑**：网关除 `/events` 外只认 `Authorization: Bearer`（不接受 `?token=`），
  所以必须用 `setDataSource(context, uri, headers)`，只给 URL 会 401。
- **视频**：`VideoView` + `setVideoURI(uri, headers)`（自带 Surface 生命周期与 MediaController）。

`ChatScreen` 的预览面板改为按类型分派（`ArtifactKind`：mime 优先、扩展名兜底），
并把任意文件都补了一个「用其他应用打开（WPS / 系统）」的通用出口。

### 🖼 SVG 一律矢量渲染（不是当源码看）

pi 画的流程图/架构图常以三种形态出现，之前只有前两种会画出来， `.svg` 文件被当文本看源码：

1. Chat 里的 ```svg 、`<svg>` HTML 块 —— 已由 `MediaAttachmentGallery → SvgPreviewCard`
   （`SvgWebViewCanvas`，WebView 原生 SVG 引擎）渲染；
2. `![图](chart.svg)` 图片语法 —— 走 `MediaKind.SVG` 从网关取字节后渲染；
3. **这轮补上**：`.svg` 文件被链接/staging 打开时，预览面板新增 `ArtifactKind.SVG`，
   用同一个 `SvgWebViewCanvas` 渲染（`enableZoom = true`，可双指缩放）+ 「预览 / 源码」切换。

顺手补的两个真实缺口：
- ````html / ```xml / 无语言标记的围栏里只要是完整 `<svg>...</svg>`（包括带 `<?xml?>` 头的 .svg 本体）
  也按矢量图渲染，而不是展示源码；
- 聊天内联 SVG 卡片高度 200dp → 260dp（上限 300dp）：流程图本来比插图高，
  之前被 CSS `max-height` 压成窄带、文字看不清。

---

## [1.7.29] - 2026-10-28

### ✨ 扫码即替换旧 Token + 访问令牌可查看（两个体验小改进）

**1. 401 不再是冷冰冰的「连接握手失败」**

轮换 Token 后手机里存的还是旧密钥，网关必定回 401。新增 `connectErrorMessage()`
把原始错误翻译成能直接行动的一句话：

| 服务端返回 | 用户看到 |
| --- | --- |
| 401 | 密钥已失效，请重新扫码配对 |
| 403 | 访问被拒绝（可能是 Cloudflare Access 或防火墙策略） |
| 502 | 网关连不上本机 pi-web，请确认 pi-web 已启动 |
| 超时 / 连不上 | 连不上服务器，请检查网关地址与网络 |

配合 1.7.27 的 `AuthInterceptor` 修复（不再用本地旧 Token 覆盖请求头），整条链路是
**扫码 → 用扫到的新 Token 校验 → 200 才原子覆盖旧配置 → 失败则保留旧配置**，
不再需要「先断开再连接」。

**2. 设置页的访问令牌可以看了**

原先只显示 `pi_e99d0fd4••••••••`，既看不出头尾也没法复制。现在：

- 默认打码为 `pi_e99d0fd4••••9c1f`（头 10 + 尾 4），一眼比对两端是否同一个密钥；
- 点「显示」/「隐藏」切换明文，旁边「复制」一键拷贝；
- 密钥本来就以明文存在 SharedPreferences，打码防的不是存储，而是屏幕
  （截图、录屏、投屏、肩窥）。

---

## [1.7.28] - 2026-10-28

### 🐛 「屏幕上出现很多个 t/s，后面几个很慢或为 0」——已定位根因并修复 (Release v1.7.28)

**pi-web 源码依据**（`.next/static/chunks/app/page-*.js`）：

```js
// 1) 状态是「单数」的：一个 store 只持一条正在流式的消息
let l2 = { isStreaming: !1, streamingMessage: null }

// 2) 只有那一条被写死传 isStreaming:!0（渲染入口 ap 是 memo，整页只此一处）
let ap = memo(function(e){ let d = ar(t, ao)   // 取当前正在流式的那一条
  return d ? jsx(sv, { message: d, ..., isStreaming: !0 }) : null })

// 3) 历史消息走另一条路径，根本不传 isStreaming → falsy → 不渲染徽标
jsx(sv, { message: n, toolResults: nu, ... })          // 没有 isStreaming

// 4) 徽标（token 数 + 胶囊）被整个包在 isStreaming 里 → 一结束就双双消失
h && (d = Math.round(Z), … ↓d … [_!=null && <span t/s>])
```

所以 pi-web 界面上**永远最多一个**徽标，而且它是「唯一正在增长的那条消息」的实时值。

**本地为何会冒出多个**：`ChatItem.isStreaming` 是逐条**粘住**的 —— 一回合里插了工具卡、
或多段正文被拆成多个 AGENT 气泡，就会同时有好几条为 true，于是每一条都挂了一个徽标：
前面的早已停止增长、停在停下那一刻的旧值（看起来“很快”），最新的那条才刚起算
（看起来“很慢 / 0”），完全对上你看到的现象。

**修复**：
- `MainActivity` 新增 `settleStaleStreamingAgents()`：新建流式气泡前、以及插入工具卡前
  （= pi-web 的 `message_end` 语义），把之前那些 AGENT 气泡的 `isStreaming` 收尾；
- `ChatScreen` 再兜一层：只有「会话最后一条且正在流式」的那条才能挂徽标
  （`liveStreamingItemId`），与 pi-web 的单数 `streamingMessage` 一致。

### 📏 徽标高度降低

Android 的文字行高由字体度量决定（比 fontSize 大 4~5sp），只改 fontSize 降不下来。
现在：`lineHeight` 钉成与字号相同、关掉 `includeFontPadding`、字号 11→10.5sp、
上下内边距 1→0.5dp、圆角 4→3dp、与箭头/数字的间距同步收紧（4/6→3/5dp）。

---

## [1.7.27] - 2026-10-28

### 🐛 流式速度徽标一直显示 0.0 t/s → 采样机制重做 (Release v1.7.27)

**现象**：pi-web 网页里很好看的 `模型 ↓ token [177.7 t/s]`，到手机上速率几乎一直是 0.0。

**根因**（三个叠在一起）：
1. pi-web 跑本机 localhost，浏览器拿到的是逐 token 的 delta，300ms 固定轮询每一拍都有增量；
   手机侧走 5G + Cloudflare Tunnel，SSE 常被聚成一批一批到达，固定轮询会有大半拍子读到
   `diff = 0`，而 pi-web 原公式会把这种情况写成 0 → 屏幕上一片 0.0。
2. `PiSseClient` 的 `/events` 请求没有显式声明 `Accept-Encoding`，OkHttp 会自动加 `gzip`，
   gzip 流必须攒满缓冲区才能解压 —— 这直接把 SSE 打成一团一团到达（正文“挤牙膏”也同源）。
3. `text_end`（一段正文讲完）被解析成 `isStreaming = null`，上层按 false 处理，于是回合中途
   就把流式指标摘了，下一段正文又从 0 起跳。

**修复**：
- 采样改为**变化驱动 + EMA 平滑**：token 没涨就保持上次的值（绝不写 0），涨了才结算；
  窗口 < 0.2s 不结算（防网络抖动放大的假峰值），> 3s（思考/等工具停顿）则重新起算，
  不拿空闲时间当分母；remount（列表滚出再滚回）从模块级缓存接着显示，不重新起跳。
- SSE 请求显式 `Accept-Encoding: identity`，不再让 gzip 把流打包。
- `text_end` 不再把 `isStreaming` 置 false，整个回合的收尾继续由 `agent_end` + `/state` 巡检判定。
- token 估算法、配色阈值、箭头坐标、显示时机与 pi-web 保持一致。

### 🗂️ 技能详情弹窗重做：近全屏 BottomSheet

- 原来的 `AlertDialog` 宽度被默认 `maxWidth` 卡死（320~360dp），Markdown 一行一句；
  正文区还固定 `heightIn(max = 340.dp)`，大半屏幕空着。现在换成接近全屏（94%）的
  `ModalBottomSheet`，正文吃满剩余高度，底部操作区固定不随正文滚走。
- **按钮排版重做**：标题行原来塞了「名字 + 全局徽标 + 效果预览 + 源码编辑」四件东西
  SpaceBetween，分段按钮被挤到逐字换行，而且作用域徽标看起来像分段控件的一部分（语义完全不同）。
  现在标题行只留「图标 + 名字 + 作用域徽标 + 关闭 ✕」，预览/源码改成单独一行的两段式分段控件。
- 路径区：两行显示 + 一键复制（不再截断成 `…\nod…`），版本与 skills.sh 外链合并到同一张卡。

### 🌿 「从此处编辑」对齐 pi-web：真实清理后续内容

- 之前只把正文填回输入框，后面的记录一直留着，继续发送就变成「旧尾巴 + 新分支」重复。
- 现在按 pi-web 的 `editFromHere` 实现：先 `POST /api/agent/{id}` `{type:"navigate_tree", targetId}`
  把会话树切到该条目（同一会话文件内部分支，不新建会话），再刷新快照；
  该节点之后的条目变成被放弃的分支，界面不再显示。
- `ChatItem` 新增 `entryId`（历史快照里 pi 条目的真实 ID），供导航使用；
  任务执行中禁止回退（与 pi-web `branchesLockedWhileRunning` 一致）。

---

## [1.7.26] - 2026-10-28

### ✨ 流式输出实时速度徽标：`模型名 ↓ token [177.7 t/s]` (Release v1.7.26)

严格对标 pi-web 的 `MessageView.tsx` assistant 头部，新增 `ui/chat/StreamingSpeed.kt`：

- **估算 token 数**：与 pi-web 的 `sf()` 完全一致 —— 宽字符（CJK / 假名 / 谚文 / 扩展 B 区）记 1、
  其余记 1/4，不调分词器；按 code point 遍历，emoji 不会被代理对拆成两个字符。
- **增量累加**：对齐 pi-web 的 `cached(prev, next)`，新文本是旧文本延伸时只估算尾巴，
  并保留「边界切在代理对中间时先退回 1/4」的保护，避免长正文逐 delta 全量重算（O(n²)）。
- **速率采样**：300ms 一次；首帧记录 baseline 且**只记一次**（`base ??=`），
  已经过 > 0.5s 才出数，因此显示的是「自流式开始的累计均速」，与 pi-web 行为一致。
  baseline 存模块级 32 条 LRU（对应 pi-web 的 `ss` 缓存），列表滚出再滚回不会重新起跳。
- **变色档位**：`>=50` 青 `#53B3CB` / `>=30` 绿 `#9BC53D` / `>=15` 黄 `#F9C22E` / 其余红 `#E01A4F`，
  白字 11sp、内边距 1×6dp、圆角 4dp；下行箭头按 pi-web 的 10×10 svg 坐标原样绘制。
- **显示时机**：仅 `role == AGENT && isStreaming` 时出现，流式结束 token 数与胶囊一起消失（同 pi-web）。
  流式徽标的显示条件**不挂在 `showModelHeader` 上** —— 最后一轮在跑时流式气泡会被归入
  `ProcessDetailsGroup`（该处 `showModelHeader=false`），挂上去就永远不显示。

### 🎛️ 会话顶栏瘦身：标题回归标题，状态不再占一整行

- 标题栏第二行原先是「状态点 + （重连文案 / Agent 阶段文案 / 会话标题）三选一」，等于把标题行当状态栏用；
  现改为纯会话标题（空白时显示「未命名会话」），正文脉冲条与底部状态已能表达完整运行状态。
- 唯一无法在正文看到的信号（事件流重连中）退化为项目名右侧一个 6dp 琥珀小点，附无障碍 `contentDescription`。
- 顶栏图标统一 18dp 图形 + 34dp 紧凑热区，顺序改为「置顶 → 用模型重写标题 → 工作目录变更 → 重新加载会话」；
  移除重复的「能力与设置中心」入口（会话列表顶栏的齿轮仍是唯一入口）。

### 🔒 SSE token 不再进 URL + 隧道日志脱敏

- `PiSseClient` 的 `/events` 请求去掉 `?token=`，只走 `Authorization: Bearer`：
  Cloudflare Tunnel / 反向代理会把完整 URL 写进访问日志，query 里的 token 等于把网关密钥明文落盘。
  网关侧 `authMiddleware` 本就接受 Bearer 头，无需改协议。
- `server/gateway.js` 新增 `pipeTunnelLogs`：按行转发 cloudflared 日志、过滤正常的
  `canceled by remote with error code 0`（手机切页面/息屏/重连都会产生），并把 `?token=` 打码。

---

## [1.7.25] - 2026-10-28

### ✨ 模型切换面板去重：去掉「默认」文字徽标，只留星标 (Release v1.7.25)

- **现象**：模型与思考级别面板里，当前全局默认模型那一行右侧同时出现「默认」琥珀色小徽标 + 实心金星 ★，
  信息重复，行尾显得拥挤（截图见 issue/反馈）。
- **修复**：`ModelAndThinkingSheet` 删除该徽标及其后 4dp 间距，默认模型只由实心金星 ★ 表达；
  非默认模型仍为浅灰空心星 ☆（点击设为默认）。右侧动作区宽度因此收窄，标题与 `provider` 徽标可用空间更多。
- **无障碍**：星标 `contentDescription` 保持「当前默认模型」/「点击设为全局默认模型」，功能语义不丢。

### 📚 文档与脚本：固定域名隧道落地记录 + 安全加固方案

- `docs/PERMANENT_TUNNEL.md` 补充本机 Named Tunnel 部署记录（`pi.codywon.top`）与 Access 会挡 App 的说明；
- 新增 `docs/SECURITY_UPGRADES.md`（Access + Service Token / Tailscale Serve / WAF 取舍与回滚）；
- `server/launcher.js` 在网关已运行时优先展示固定公网地址，而不是局域网 IP。

---

## [1.7.24] - 2026-10-28

### 🗄️ 左边栏页脚完全对标 pi-web：打开其他项目与全量归档同步视图 (Release v1.7.24)

- **左侧工作区抽屉底部页脚重构（1:1 严格对标 pi-web `SessionTree.tsx`）**：
  - 废除原先居中粗边框的大卡片样式，改为与 pi-web 侧边栏一致的轻量无框左对齐列表项（`footer-open` 与 `footer-archived`）；
  - **打开其他项目**：小图标（16dp 文件夹）+ `打开其他项目...`，点击直达项目添加/浏览选择弹窗；
  - **已归档**：小图标（16dp 归档箱）+ `已归档 · {count}` + 右侧 `ChevronRight` 小箭头。
- **全量归档管理视图（1:1 对标 pi-web `SessionSidebar.tsx` `sidebar-archive-view`）**：
  - 点击「已归档」抽屉平滑切入归档专属视图，顶部为 `← 已归档 · {count}` 返回按钮与 `⋯` 操作菜单（支持一键「全部恢复」与「清空已归档」）；
  - 归档列表按项目分组展示（完全对标 `buildArchiveRows` 结构），每项展示完整标题与时间戳，右侧附单项「恢复」（`Unarchive`）与「删除」图标；
  - 恢复/归档操作通过 `POST /api/sessions/ui-state` 实时写回 pi-web 服务端，与 PC 网页端双向即时对齐；点击任意归档会话自动关闭抽屉并进入会话。
- **网关 Cloudflare 固定域名命名隧道支持**：
  - 网关接入 `PI_TUNNEL_NAME` + `PI_TUNNEL_HOSTNAME` 环境变量，支持固定域名 Named Tunnel，彻底解决临时 Quick Tunnel 重启换域名的痛点；
  - 补全 WebSocket 协议升级握手独立鉴权拦截。

---

## [1.7.23] - 2026-10-28

### 🎨 技能中心 · skills.sh 搜索结果排版重做 (Release v1.7.23)

- **现象**：搜索结果卡片又高又空，`skills.sh ↗` 被挤成逐字竖排（`sk` / `ill` / `s.` / `sh`），
  仓库名和安装量也跟着错位；安装按钮悬在右侧中间，卡片中间一大片空白。
- **原因**：卡片内层是一行不限宽、无省略的 `Row`：「仓库名 · 安装量 · skills.sh ↗」全挤在一行里，
  长仓库名（如 `heygen-com/hyperframes`）把后面的文字压到只剩几十 dp，文本只能逐字换行；
  标题也不限行，卡片被顶高。
- **修复**：改成紧凑两行式
  - 第一行：技能名（13.5sp SemiBold，单行省略）；
  - 第二行：`owner/repo`（10.5sp 等宽，可压缩省略） + ` · 安装量`（绿色，不换行）；
  - 右侧固定动作区：外链改成 **图标按钮**（`OpenInNew`，不再有会被挤竖排的文字），
    紧跟「安装 / 安装中 / ✓ 已安装」胶囊（安装按钮 32dp 高、圆角 8dp）。
  - 所有文本 `maxLines = 1 + softWrap = false`，卡片高度因此稳定，不再随仓库名长短跳动。

---

## [1.7.22] - 2026-10-28

### 🧹 串会话审计补漏 + 左滑返回定位到原工作区 + 首屏加载圈 (Release v1.7.22)

**A. 串会话：又找出三处没盖住的路径，已补齐**

- **SSE 事件流的会话守卫**：以前只靠“切会话就 cancel 旧流”保证不串，现在 collector 里再加一道
  `if (currentSession?.id != sessionId) return@collect` —— 旧会话的事件一条也不会写进当前列表。
- **附件按会话隔离**：`attachments` 之前是 `remember { ... }`，在 A 会话选的文件切到 B 后仍在，
  会跟着 B 的消息发出去。现在改成 `remember(sessionId)`。
- **滚动位置按会话隔离**：`listState` 之前是全局 `rememberLazyListState()`，切到新会话会沿用
  上一个会话的滚动偏移（看起来像打开了一个既不在头也不在尾的会话）。
  现在 `remember(sessionId) { LazyListState() }`，`userJumpedToHistory` / `hasInitialScrolled` 也改按 sessionId 存。
- 已完成守卫全表：快照合并、SSE 事件、自动补页、翻页、磁盘缓存回放、发送/引导/排队/审批/停止/压缩/
  完整输出、任务收尾（`completeAgentSessionRun`）。

**B. 左滑返回回到的不是刚才那个工作区**

- 根因：`selectedWorkspacePath` 用的是 `remember(workspaces)`。`sessions` 一刷新，`workspaces` 内容就会变
  （运行数/排序/新会话），key 失效 → 选区被重置到“第一条”（按运行数/会话数排序后的第一个）。
- 修复：选区不再随列表刷新重置；新增 `initialWorkspacePath` 提示（主界面在打开/创建会话时记下它所属工作区），
  返回列表时**只应用一次**到该工作区；之后用户手动切换不会被抢回。

**C. 首屏加载圈**

- 会话内容没到之前，屏幕正中展示一枚 28dp 加载圆圈（不再是一整片空白或骨架条），
  加载完成后自然消失；真·空会话仍会提示“还没有对话，发送指令开始”。

**D. 顺带修掉 v1.7.21 技能功能的编译错误（那次发版因此没出包）**

- `GlobalSettingsScreen` 里 12 处 `xxx.package` 改为 `` xxx.`package` ``：`package` 是 Kotlin 硬关键字，
  不加反引号是语法错误（编译器报 “Qualified expression without selector”）；
- `PiApiClient.sanitizeCwd()` 用到的 `prefs.lastSelectedCwd` 之前根本没定义，已补上该偏好项，
  并在打开/创建会话时记录当前 cwd（技能接口拿不到 cwd 时的兜底）。

---

## [1.7.21] - 2026-10-28

### 🥞 深度复刻 pi-web 技能管理：项目/全局双维度隔离 + skills.sh 技能市场搜索与安装 + 模型可见性与手动调用开关 (Release v1.7.21)

- **项目与全局双维度分层管理**：
  - 完美复刻 pi-web 技能架构，分为「📁 项目技能」与「🌐 全局技能」两组展示与统计计数（例如 `1/1`、`10/10`）；
  - 项目技能严格绑定当前工作区（安装与持久化在 `<cwd>/.pi/skills/`），全局技能保存在 `~/.pi/agent/skills/`；
  - 提供分组总开关（Group Switch），可一键批量对模型开启或隐藏整个分组的所有技能（底层调用 `PATCH /api/skills` 批量 `filePaths` 操作）。
- **完整复刻 skills.sh 技能市场搜索与在线安装**：
  - 新增「➕ 添加技能 (skills.sh)」专属界面，直连官方 skills.sh 技能市场；
  - 支持快捷切换「全局」与「项目」安装目标范围，并在界面直观预览安装目录路径；
  - 搜索栏支持实时输入搜索（如 `react`、`testing`、`deploy`），并内置高频关键词快捷标签；
  - 搜索结果卡片完整展示技能名称、代码仓库、安装量统计（如 `1.1M installs`）、`skills.sh ↗` 官方主页外链，并精准识别「✓ 已安装」、「安装中...」和「安装」状态；
  - 具备网关代理与直连官方 API 的双重故障自愈高可用容灾能力。
- **模型可见性开关（对模型隐藏但仍可手动调用）**：
  - 完整实现 pi-web 的 `disable-model-invocation` 机制：开启时技能提示词公开给大模型自动调度；关闭时对模型隐藏（不消耗提示词 Token），仍可在会话中通过 `/<技能名>` 或手动命令显式调用；
  - 列表项配备状态指示灯（🟢 绿色表示模型可见，⚪ 灰色表示对模型隐藏但保留手动调用）与对应说明文本；
  - 技能详情弹窗支持直接切换模型调用开关、查看安装路径、访问 skills.sh 源码链接、版本哈希比对、检查远程更新、自定义技能别名，以及 SKILL.md Markdown 渲染预览与源码在线编辑保存。

---

## [1.7.20] - 2026-10-28

### 🎯 时间轴点位直接命中 + 会话切换不再串会话 (Release v1.7.20)

**A. 右侧时间轴：点不准、点不动、要点隔壁一个点才生效**

- 原因一（触控死区）：导轨是 `Column + SpaceBetween + 固定 18dp 热区` —— 画出来的点只有 7dp，点与点之间是大片空洞，**点在缝里完全没反应**；节点多时（>24）总高还会撑出 `Surface` 边界被裁掉，后面的轮次干脆点不到。
- 原因二（跳转被自打）：跳转用 `animateScrollToItem`，被流式输出触发的「自动跟到底部」或长列表动画打断，表现出来就是“点了一下没反应”。
- 原因三（落到下一回合）：抽屉里点「最终答复」是固定 `行号+1`，没有最终答复时会直接落到下一个回合。
- 修复：每个节点用 `weight(1f)` 占满自己那一段，**热区首尾相接、整条导轨无死区**（节点上限从 24 提到 40，>20 时点自动缩小）；跳转改用瞬时 `scrollToItem` 并夹到有效行；「最终答复」按真实结构算行号；用户自己滑回底部后自动恢复“跟随新消息”。

**B. 串会话风险（核实出两条真实路径，已修）**

- **快照合并无会话校验（最严重）**：打开 A 后立刻切到 B，A 的慢响应回来后会直接把 A 的 `messages`/统计并进正在显示的 B。现在每次拉取回来先比对 `currentSession.id`，不一致整份丢弃 —— 与 pi-web 的 `if (tf.current !== e) return null` 同款做法。
- **发送绑定错会话**：带附件发送要先 await 上传/编码，旧代码在 await **之后**才读 `currentSession!!.id`，期间切了会话就会把正文发到另一个会话里。现在发送 / 引导 / 排队 / 审批 / 停止 / 压缩 / 完整输出全部绑定到当前屏幕的 `activeSessionId`，并且只有仍在该会话时才往界面插气泡、改忙碌态。
- 附带：`completeAgentSessionRun` 增加 `isCurrent` 守卫，别的会话跑完不会再消掉当前会话的忙碌态。

---

## [1.7.19] - 2026-10-28

### 🔄 发送后一直「正在等待模型」而正文不同步（自己踩掉自己的 SSE）+ 排队面板压缩 (Release v1.7.19)

**A. 发送指令后一直「正在等待模型」，而 PC 侧已经在跑**

- 根因（自我踩踏的死循环）：
  1. pi 执行工具/等 bash 时 `isStreaming=false`，但 `isPromptRunning=true` —— pi-web 前端也是这么区分的（`isStreaming ? waiting_model : running_command`）；
  2. 旧代码把 SSE `connected` 里的 `isStreaming=false` 当成「任务结束」，直接把 `isAgentBusy` 清成 false；
  3. 下一轮巡检（1.5s）又看到服务端 `runningSessionIds` 包含本会话，以「服务端在跑、本地没在跑」为由 **重新挂载 SSE**；
  4. 重挂会 `cancel()` 掉正在收事件的那条流 —— 于是每 1.5s 自我踩踏一次，增量事件全落在断开窗口里，正文永远同步不下来，标签永远停在「正在等待模型…」。
- 修复：
  - `connected.isStreaming=false` **不再**修改运行态（是否结束只由 `/api/sessions/{id}/state` 的 `running` 与 `agent_end` 防抖判定）；
  - 移除 `syncSessionSnapshot` 里「服务端在跑就无条件重挂 SSE」的逻辑；
  - 改为巡检里的**健康检查重挂**：流已断，或连上了但 >15s 没有任何事件，才重挂，且 15s 节流；
  - 重挂能真的补回正文：pi-web 重连会下发 `message_start`（当前生成中的 message 快照），现在把它解析成 `text_replace` 整段回填；`text_replace` 在本地还没有 agent 气泡时会新建一条（以前直接丢掉）。

**B. 排队消息面板按 pi-web 重做（不再是一张大卡片）**

- 头部：左侧 `QUEUED (n)`（10sp 等宽小字），右侧一枚小的 **放回输入框** 按钮（图标 + 文字，pi-web 的 recall）；
- 每条：左侧是 `steer` / `follow-up` 胶囊标签（steer 用主色、follow-up 用弱色，与 pi-web 同款），右侧单行文本超长省略；
- 容器只留 1dp 描边 + 6dp 圆角，去掉原来的大色块；超过 4 条时只铺前 4 条并提示「还有 N 条排队中」；
- 点「放回输入框」会把 steering + follow-up **全部**按空行拼回输入框（与 pi-web 的 recall 行为一致），同时清空服务端队列。

---

## [1.7.18] - 2026-10-28

### 🎯 根因找到了：打开会话必须点一下「上滑加载」才出正文（v1.7.7 引入） (Release v1.7.18)

- **现象**：打开历史会话，中间只显示「还没有对话，发送指令开始」，顶部却显示「上滑加载更早的 N 轮」；点一下顶部，正文瞬间全部出来。
- **根因（一行 remember 写错）**：
  ```kotlin
  // v1.7.7 起的写法
  val conversation = remember(olderMessages, messages) { olderMessages + messages }
  ```
  `messages` 是 Compose 的 `SnapshotStateList`，它**没有实现结构化 equals**（不是 `AbstractList` 子类，用的是引用相等）。所以 `remember` 的 key 永远是同一个实例、永远相等：首屏在 `messages` 还是空的时候就把拼接结果缓存成了空列表，之后快照把 `messages` 填满也**不会重算**。而 `olderMessages` 是普通 `List`，翻页时会换新实例 —— 所以点一下「上滑加载」就让 key 失效，正文突然全部出现。
- **修复**：直接拼接 `val conversation = olderMessages + messages`（不再缓存），并去掉 `leadingRowCount` 上同样无效的 `remember`。
- 实测接口没任何问题：出事会话 `?tail=60` 返回 117 条消息（2 user / 58 assistant / 57 toolResult，`turnsBefore=4`，可产出 64 个可见条目）—— 确实是渲染层把它当成空列表。
- 保留 v1.7.17 的兵底：首屏自动补齐 + 磁盘快照回放 + 静默骨架（正常情况下不再触发）。

---

## [1.7.17] - 2026-10-28

### 📖 打开会话立刻出正文 · 首屏不再空白等「上滑加载」 (Release v1.7.17)

**A. 打开会话是空白，必须手点顶部「上滑加载」才会出全部历史**

- 现象：进入会话后中间空白，顶部「上滑加载更早的 N 轮」、底部「正在等待模型…」；点一下顶部哨兵，历史瞬间全部出来。
- 真机对应的会话实测：`GET /api/sessions/{id}?tail=60` 返回 115 条消息（2 条 user、58 assistant、55 toolResult），但 App 首屏解析出的 `messages` 为空，而 `hasMore/turnsBefore` 正常（所以顶部哨兵能显示）。
- 修复（三道保险）：
  1. **首屏自动补齐**：服务端快照解析不出任何消息时，自动向前翻最多 3 页直到有内容可渲染 —— 用户不需要再手点顶部，也不再弹 Toast；
  2. **磁盘快照回放**：每次成功拉到会话快照就把原始 body 落到 `cacheDir/session-snapshots`（每会话 10s 内最多写一次、只保留最近 8 个），再次打开（包括进程被回收后）先解析上周的快照立刻画正文，再让网络结果覆盖；
  3. 模型/统计/翻页游标统一由 `applyHistoryMeta()` 落地，内存缓存、磁盘缓存、网络快照三路行为一致。

**B. 首屏状态噪声清零**

- 首屏还没有正文时：只画静默骨架，不再出现「等待模型返回首条消息…」；
- 底部「正在等待模型…」脉冲与顶栏副标题只在**已经有正文**后才显示（在 PC 侧真的运行时才出现，不再是空白页上的假状态）；
- 首屏加载期间顶栏显示会话标题，不再被「正在等…」截断。

**C. 一并发布的两处既有修复**

- 模型切换失败（典型：`Model not found`）现在会**回滚**选择并提示原因，不再出现手机显示的模型与 PC/会话保存的模型永久错位；
- 错误提示优先展示服务端的人类可读错误（如 `Model not found: CloseAI/deepseek-flash`），不再糊一屏 `{error:…}` JSON。

---

## [1.7.16] - 2026-10-28

### 🩺 运行态校准 · 打开会话秒开 · 斜杠命令改为 "/" 触发 (Release v1.7.16)

**A. 已完成的任务点进去也永远显示「正在等待模型…」（根因已实测确认）**

- `GET /api/sessions/{id}/state` 顶层的 `running` 只表示「pi-web 内存里有一个活着的 agent 运行时」，**不是「正在执行」**；pi-web 前端自己的判定口径是 `running && (state.isStreaming || state.isPromptRunning)`。
- 关键实验：挑 3 个磁盘老会话（`running:false`）只订阅 `/api/agent/{id}/events` 2.5s 再断开，三个会话全部变成 `running:true`，但 `isStreaming / isPromptRunning` 仍为 `false`，也**不会**进入 `runningSessionIds`。也就是说：**App 打开任何会话都会给它唤醒一个运行时**，旧代码把顶层 `running` 当运行态 → 每个点进去的任务都成了「正在等待模型…」，而列表徽章又是对的，看起来像「状态没同步」。
- 现在严格按 pi-web 口径判定运行态，并把「有运行时」单独记为 `runtimeAlive`；`isBashRunning` 一并纳入（命令还在跑时不会误判完成）。
- 任务结束 / 手动停止后立即把列表与当前会话的运行态置回「已完成」，不再依赖下一轮对账。

**B. 打开会话不再「空白 + 正在同步 + 等待模型 + 历史没加载」四连**

- 状态与历史快照**并行**拉取（原先串行，正文白等一个 RTT）；
- 快照请求改用 pi-web 同款参数 `deferMedia=1&tree=summary`（单个会话实测 200KB → 150KB）；
- 列表页每 4s 拉 240KB 的 `/api/sessions` → 改为每 2.5s 只拉 `/api/agent/running`（**约 230 字节**），只有 `sessionListVersion` 变化才重拉完整列表；
- 会话页空闲时不再每 2.5s 下载 200KB 快照：只有「本会话在跑 / 列表版本变化 / 15s 兜底」才拉；
- 首屏加载态改为**静默骨架**，删除「正在同步会话历史…」；顶部提示由「更早的 N 轮还没加载 · 上滑加载」改为中性的「上滑加载更早的 N 轮」。

**C. 输入框左侧的「/」按钮已移除，改为输入 `/` 就地唤出命令列表**

- 列表在输入框上方展开，按命令名/描述实时过滤，带「正在读取命令列表…」轻态与「没有匹配的命令」空态；
- 选中即填入 `/命令 `（尾随空格让列表自然收起）；底部弹层整段删除，输入行更干净。

---

## [1.7.15] - 2026-10-28

### 📂 常用置顶完全对齐普通项目目录样式 (Release v1.7.15)

- **抽屉中「常用置顶」样式 100% 还原普通项目目录**：
  - 移除单独的加粗/彩色高亮差异与专属黄色图钉/黄色数字徽章，统一采用与常规项目目录一致的文件夹图标、内边距（`10.dp, 8.dp`）、图标间距（`8.dp`）与标准徽标样式；
  - 选中态使用统一的选中主题主色（`theme.primary`），未选中态为透明无框无背景填充；
  - 顶栏工作区标题去除 `🌟` 特殊前缀，保持干净统一的「常用置顶」；
  - 仅作为项目工作区列表中永久置顶在第 1 位的目录项存在。

---

## [1.7.14] - 2026-10-27

### 🧹 模型弹窗里不再出现已删除的 CloseAI (Release v1.7.14)

- **现象**：`models.json` 里早就只有 `codywon` 了，但「模型切换」弹窗里仍能看到 `CloseAI`（17 个模型）。
- **根因（实测）**：`GET /api/models` 的 `modelList` 里确实还有 `CloseAI` —— pi 的模型清单会把**历史会话里用过的模型**一并并进来（`CloseAI:gpt-5.6-terra` 这类键出现在 174 个历史会话里），所以 `POST /api/models/refresh` 返回 `changed:false`，清 `models.json` 也刷不掉。另外 `~/.pi/agent/settings.json` 的 `defaultProvider` 还指着 `CloseAI`，导致 `/api/models` 的 `defaultModel` 也是幽灵模型。
- **修复（App 侧，自包含）**：`getDynamicModels()` 只保留「**在 models.json 里声明过** 或 **pi 凭据库里已配置 Key**」的 provider —— 其余（历史遗留/幽灵）一律不展示。实测本机过滤后只剩 `codywon` 的 7 个模型；带 Key 的内置 provider 不受影响。
- **顺带修默认模型**：若服务端默认模型落在被过滤的 provider 上，自动回退到「同名模型」或第一个可用模型（例如 `CloseAI/deepseek-flash` → `codywon/deepseek-flash`），选择器一打开不会再是幽灵模型。
- **本机配置清理**：`~/.pi/agent/settings.json` 的 `defaultProvider/defaultModel` 由 `CloseAI/deepseek-flash` 改为 `codywon/deepseek-flash`（改前已备份 `settings.json.bak-before-closeai-cleanup-*`），刷新后 `/api/models` 的 `defaultModel` 已变为 `codywon/deepseek-flash`。

---

## [1.7.13] - 2026-10-27

### 📌 置顶任务只活在「常用置顶」，置顶卡片不再着色 (Release v1.7.13)

- **项目工作区里不再出现已置顶的任务**（对齐 pi-web 的侧栏模型）：
  - pi-web 的行构建逻辑是「先挑出 pinned 进入 Pinned 区，**其余才按项目分组**」（`if (H(family,...)) { pinned.push(...); continue }`），所以置顶会话不会在项目分组里重复出现；
  - App 之前是「项目工作区里先出一个『置顶任务』分区，再从『所有任务』里排掉」——列表顶部仍会重复展示置顶项。现在项目视图直接**整段排除**置顶会话，它们只出现在「🌟 常用置顶」；
  - 顶部「全部/运行中/已完成」三个计数同步改为**可见集合**（不含置顶），避免「全部 (60) 但只列出 59 条」的错位；
  - 项目中仍有置顶任务时，列表顶部给一行可点的轻提示「N 个置顶任务已归入『常用置顶』」（点击直达该区）；若整个工作区都被置顶，空态文案改为「本工作区的 N 个任务都已置顶，见『常用置顶』」，不会出现"空工作区"错觉。
- **置顶卡片不再着色填充**：
  - 去掉置顶态的蓝色底填充与主色描边，以及卡面上那个「置顶」彩色胶囊、标题加粗——置顶区里每张卡都是置顶，这些强调纯属噪声；只保留右侧一枚小黄图钉；
  - 卡片着色只留给**需要立刻注意**的状态：等待确认（琥珀）、运行中（翠绿）。

---

## [1.7.12] - 2026-10-27

### 🧾 bash 完整输出 / 文件预览 / 待审批通知 (Release v1.7.12)

> 这一批严格按「不新增顶栏图标、不改既有行高」来做：所有新能力都挂在**已有工具卡片**里，收起态外观零变化。

- **A. 长命令的完整输出能看了**（近 7 天你的 bash 调用占 54%，最长一次 12m50s）：
  - pi 会把长输出落到临时文件，聊天里只有截断预览；工具结果里的 `fullOutputPath` 现在会被解析出来，
    卡片「执行结果」标题行右侧多一个 **完整输出** 入口 → `GET /api/agent/{id}/bash-output?path=…`（实测 `{success:true,data:{output}}`，过大返回 413 会提示）。
- **B. 文件一键预览**（read+edit 合计 9,772 次调用）：
  - `read/edit/write/ls/find/grep` 这类工具的入参里若带**绝对路径**，卡片上会出现 **查看文件** 入口（绝不猜相对路径，避免误判）；
  - 预览面板按类型分流：`?type=meta` 取大小/语言/mime，`image/*` 走 `?type=download` 解码成图，其余走 `?type=read` 以等宽字体展示并支持一键复制。
- **C. 待审批不再只靠震动**：
  - 收到需审批的高危命令时，除了原来的震动，另发**独立高优先级通知**（标题「⚠️ 待审批 · 会话名」+ 工具名/命令预览），点通知直达该会话并清掉通知；该通知**不看**"任务完成通知"开关——被静音等于任务卡死。
  - 震动改为两段重震，与「任务完成」的两下轻震区分。

---

## [1.7.11] - 2026-10-27

### ⚙️ 自动压缩/重试开关 + 工作目录 git diff (Release v1.7.11)

- **pi 的两个智能体开关原先手机端看不到也改不了**：
  - `get_state` 本来就返回 `autoCompactionEnabled` / `autoRetryEnabled`，现在解析进来，并在「设置 → 系统设置」新增**智能体行为**卡片（自动压缩上下文 / 自动重试），切换即写回 PC：`{"type":"set_auto_compaction","enabled":bool}` / `{"type":"set_auto_retry","enabled":bool}`，失败自动回滚开关状态。
  - 备注：这两个开关作用于**当前会话的运行时**，未进入会话时切换会提示先打开会话。
- **工作目录的 git 变更与 diff 打通**：
  - `GET /api/git/status?cwd=` → `{isGitRepository, repositoryRoot, files:[{filePath,status,code,indexStatus,worktreeStatus}], additions, deletions}`（实测某仓库 273 个变更 / +20710 −7157）；
  - `GET /api/git/diff?cwd=&path=` → `{supported, status, patch}`（unified patch）；
  - 会话页顶栏新增「工作目录变更」入口：先看 `＋N −M · X 个文件` 与文件清单（M/A/D/?? 配色），点文件即渲染该文件的 patch（等宽、可横向滚动、支持二进制/不支持提示）。

---

## [1.7.10] - 2026-10-27

### 🔎 服务端全文搜索 + 斜杠命令面板 + 未读标记 (Release v1.7.10)

- **搜索只能搜标题（本地过滤），搜不到正文**：
  - 新增 `GET /api/sessions/search?q=…`（≤200 字符）。返回按**命中条目**展开：`{results:[{session, entryId, blockIndex, match, before, after}], truncated}`，每条都带会话本身 + 命中前后各 80 字片段（实测 `q=Wren` → 25 条 / 29.8 KB / 3.0s，服务端要扫全部会话）。
  - 列表页搜索框改为 **500ms 防抖 + 服务端检索**，顶部展示「正文命中 · N 条」卡片（会话名 + 片段，点一下进会话），本地标题/首句过滤继续保留兜底。
- **打不出斜杠命令（prompt 模板 / 技能 / 扩展命令）**：
  - 新增输入框左侧「/」按钮 → 底部面板列出来自 PC 的命令：`POST /api/agent/{id} {"type":"get_commands"}` → `{commands:[{name, description, source: extension|prompt|skill}]}`；点选即把 `/命令 ` 填进输入框。
- **未读标记**：
  - pi-web 的未读**只存在浏览器 localStorage**（键 `pi-web:unread-session-ids`），本质上不跨端；App 按同样语义做本设备未读：打开会话即已读，未读会话在列表状态位显示蓝色 NEW 图标。
- 说明：上一批列出的 `autoCompaction/autoRetry` 开关、终端、git diff、subagents 仍未做，排在下一批。

---

## [1.7.9] - 2026-10-27

### 🔁 P0+P1 同步补齐：会话改名、文件上传、项目置顶排序、批量归档 (Release v1.7.9)

- **会话改名只写本地，PC 端看不到（P0）**：
  - pi 的会话名存在 `jsonl` header 里，`GET /api/sessions` 的 `name` 字段就是它；App 之前只写 `SharedPreferences.customTitles`；
  - 新增 `setSessionName()` → `POST /api/agent/{id} {"type":"set_session_name","name":...}`（pi 命令表里确认的 type），手动改名与大模型生成标题后都会写回，并重新拉一次列表让服务端名字生效。
- **非图片文件根本没上传（P0）**：
  - 之前只把文件名拼成 `[文件附件: xxx]` 文本；
  - 现在走 pi 的上传接口：`POST /api/files/<工作目录>?type=upload&conflict=overwrite`，`multipart/form-data`、字段名固定 `files`、单文件 ≤25MB / 合计 ≤100MB（实测本机返回 `{"uploaded":["uptest.txt"]}`）；
  - 上传成功后把「已上传文件到工作目录: xxx」写进正文，agent 在 cwd 里直接可见；超过 25MB 或读取失败的文件自动跳过并提示。
- **项目（工作区）置顶与排序没同步（P1）**：
  - 与会话置顶同属 `ui-state`：`projects{pinnedAt}` + `projectOrder`；写入用 `pin-project`（`{action,projectKey,root,pinned}`，实测缺 root 会 400）与 `move-project`（`{projectKey,anchorKey}`）；
  - 抽屉里的项目排序/置顶现在以 PC 为真源（取不到时回退本地偏好），上移/下移会同时提交 `move-project`。
- **「归档 7 天前」批量操作只写本地（P1）**：
  - 新增 `setSessionsArchived(ids, archived)`，一次请求提交全部 id（`{"action":"set","ids":[...],"archived":true}`），本地标记由回调同步。

---

## [1.7.8] - 2026-10-27

### 🔌 补齐四处与 pi-web 的同步：运行态/排队、置顶归档、图片上传、权限切换 (Release v1.7.8)

- **省流量省电：判「是否在跑」不再每 1.5s 拉 273 KB**：
  - 原先 `getAgentRunningState()` 每次 GET `/api/sessions`——实测 **273 KB / 390 条会话**，会话页 1.5s 一轮；
  - 改用 `GET /api/sessions/{id}/state`（实测 **9 ms / 39 KB**，其中 28 KB 是 systemPrompt），一次拿齐 `running` + `contextUsage` + `queuedMessages` + `pendingMessageCount`，并复用于上下文百分比，不再一轮两次请求。
- **排队消息离开页面就没了**：
  - **根因**：`queuedMessages` 只存在内存里，且从未与 PC 对账；
  - **修复**：每轮状态同步用 `queuedMessages.steering / followUp` 与本地对账（本地刚改过队列 3 秒内不覆盖，避免把刚排的消息误清）；撤回队列同样打时间戳。
- **置顶 / 归档真正与 pi-web 打通**：
  - **根因**：App 自建了一份 SharedPreferences 置顶/归档，pi-web 把这两项存在自己的状态文件里（`/api/sessions/ui-state`，带 `revision`），两边互不相干；
  - **修复**：新增 `getSessionUiState()` / `setSessionPinned()` / `setSessionArchived()`（`POST {"action":"set","ids":[...],"pinned":true}`，实测 200），`loadSessions` 以服务端为准回写本地镜像，置顶/归档先本地乐观切换再写回 PC，失败回滚并提示。
- **输入框的图片根本没传给 pi**：
  - **根因**：旧实现只把文件名拼成 `[图片: xxx.jpg]` 文本，pi 收不到任何图像数据；
  - **修复**：按 pi-web 的请求校验规则真正上传——`images: [{type:"image", data:<纯 base64>, mimeType:"image/*"}]`（≤10 张、每张 ≤10MB、base64 不带 `data:` 前缀），发送 / 引导(steer) / 排队(followUp) 三条通道都带图；纯图片消息补一句可见文本，避免空 message 被拒。
- **切换权限模式报 500**：
  - **根因**：pi 在会话运行中会明确拒绝（HTTP 500 + `Cannot change tools while the session is running`），App 把 500 原样糊给用户；
  - **修复**：把服务端 `error` 解析出来，运行中提示「任务运行中不能切换权限模式，请等本轮结束后再切换」。（实测同一接口在空闲会话上 `set_tools` 返回 200。）
  - 备注：切换工具会**重建会话运行时**（服务端回 `recreated`），App 已按返回的 sessionId 重新挂 SSE。

---

## [1.7.7] - 2026-10-27

### ⬆️ 上滑翻页真正接通 PC + 一键到底真正到底 (Release v1.7.7)

- **上拉到顶只剩「本次已加载的起点 · 共 94 条记录」，再也翻不动**：
  - **根因**：v1.7.4 的自动续批只在**本地已加载的那批记录**里扩容；顶到本地边界（`windowStartIndex == 0`）后没有任何通往 PC 的兜底路径 —— 旧版那个「展开全部」按钮也只是重新取一次同样的 tail=60。等于**根本没有服务端翻页**。
  - **修复**：按 pi-web 的游标方案接上 `GET /api/sessions/{id}/context?tail=60&before=<oldestEntryId>`。新增 `PiApiClient.getOlderContextPage()`（返回 `messages / oldestEntryId / hasMore / turnsBefore`），`SessionHistoryData` 补上 `hasMoreOnServer / oldestEntryId / turnsBefore / leafId`。
  - **数据隔离，绝不破坏快照对账**：翻回来的历史进**独立的 `olderMessages`**（只 prepend），主快照 `messages` 继续按原逻辑按下标对账 —— 两条通道互不重叠，避免把 `syncSessionSnapshot` 的按索引合并搞乱。
  - **翻页后视口不跳**：前插一整页会让回合编号整体后移、行 key 全变（LazyColumn 基于首个可见 key 的索引维持失效）。翻页前记录「首个可见回合的提问消息 id + 像素偏移」，翻页回来后按该 id 找回新行号 `scrollToItem(row, offset)`，阅读位置钉住。
  - 顶部提示改成 pi-web 同款口径：**「更早的 N 轮还没加载 · 上滑加载」**（N 来自服务端 `turnsBefore`），滑到顶自动拉页，加载中显示细圆圈。
- **一键到底点完还是没到底**：
  - **根因**：用「算出来的行数」`animateScrollToItem(count-1)`。行数估算偏一点、或者动画期间又来了新消息（截图里任务正在跑），就会停在半路。
  - **修复**：改为**按像素滚到底** —— `while (canScrollForward) listState.scroll { scrollBy(6000f) }`（带 400 次护栏）。首屏定位、「跟随新消息」、编辑后回到最新、右下角回到底部按钮全部走这一条路径，不再依赖任何行号估算。
- **顺带简化**：删掉 v1.7.4 引入的本地「渲染窗口」（`visibleWindowCount / isAllHistoryExpanded / turnRowCount 行数补偿` 那一整套）。LazyColumn 本身就是虚拟化列表（只组合可见项），这层窗口不但没省资源，还是「空白正文」「翻不动」「跳一半」的共同来源。现在：**加载多少就渲染多少，翻页交给游标，滚动交给像素**。
- **右侧时间轴同步对齐**：抽屉里的入口改为「更早的 N 轮还没加载 · 点击加载」；跳转偏移改用 `turnStartRowIndex()` 精确行号（含起点横幅与提问前的系统消息行）。

---

## [1.7.6] - 2026-10-27

### 📊 上下文占用恒为 100% 修复：改用 agent 运行时的真实窗口 (Release v1.7.6)

- **左下角上下文占用一直顶格 100%（且永远是红色告警色）**：
  - **根因**：`GET /api/sessions/{id}` 的 `context.model` 只有 `{provider, modelId}`，**没有 `contextWindow`**；App 在没有本地覆盖配置时兜底成硬编码的 `200000`。而这条会话的模型 `codywon/deepseek-flash` 在 `models.json` 里是 **`contextWindow: 1000000`**。分子（最后一条 assistant 的 `input + cacheRead` ≈ 438,919）除以 20 万得到 219%，被 `coerceIn(0f,1f)` 截成 100%。
  - **实测对齐**：`POST /api/agent/{id} {"type":"get_state"}` 返回 `contextUsage: {percent: 43.8919, contextWindow: 1000000, tokens: 438919}` —— pi 自己的 `getContextUsage()` 就是权威值，pi-web 前端也是直接用它（`get_state`）。
  - **修复一（权威值）**：新增 `PiApiClient.getAgentState()`，`syncSessionSnapshot` 每轮用 `contextUsage` 覆盖 `contextTokens / maxContextWindow`；顺手对齐 PC 端可能切换过的 `model` 与 `thinkingLevel`。
  - **修复二（静态兜底）**：`getDynamicModels()` 把模型目录里各模型的真实 `contextWindow` 缓存为 `provider:modelId → window`，`getSessionDetail` 的兜底顺序改为「本地覆盖 > 会话快照 > 模型目录 > 20 万」，即使还没拿到 `get_state` 也不会算错。
  - **验证**：同一台机器另一条空闲会话 `get_state` 返回 `percent 21.37 / window 1000000 / tokens 213707`（9ms），修复后不再误报满载。
- 附带说明：底部 `⚡ cache R` 是**整场会话累计**缓存读取（来自服务端 `stats.tokens.cacheRead`），与单条消息行里的 `cache R` 不是同一口径，两者本来就不该相等。

---

## [1.7.5] - 2026-10-27

### ✂️ 设置页文案瘦身：描述一律一句话，镜像风险可查 (Release v1.7.5)

- **去掉实现细节类描述**：任务完成提示音副标题原为「任务完成时播放提示音（对标 pi-web 提示音）」→「任务完成时播放提示音」，不再向用户暴露内部对标关系。
- **设置页描述统一提炼为一句话**（超过 20 字的说明全部改写）：
  - 默认推理思考档位：「为新启动的任务设定全局默认思考深度，也可在每个会话内随时切换。」→「新任务默认思考深度」
  - 模型与提供商管理：「支持添加自定义提供商与模型、配置各厂商 API 密钥、直接编辑 models.json …」→「增删提供商与模型、配置 Key 与参数」
  - 智能体技能包：「全局与工作区工程技能包。点击卡片或「编辑」按钮可查看与在线修改 SKILL.md 源码…」→「全局与工作区技能包，点卡片编辑 SKILL.md」
  - MCP：「配置 Model Context Protocol 协议服务器，支持新增、编辑、启停节点…」→「MCP 服务器与 Code Mode 预算」
  - 插件扩展、应用主题、后台保活、主机网关、关于与升级同步精简；`~/.pi/agent/models.json` 写法改为「直接编辑 models.json」。
- **第三方加速镜像：说清连哪个站、有无风险**：
  - 副标题原本泛泛而谈（「国内网络下可提升下载速度…」），改为直接点名中转站点「ghfast.top / ghproxy.net / gh-proxy.com 中转」；
  - 行尾加一个 ⓘ 入口，点开后三句话说完：只加速安装包下载（检测新版本仍走 `api.github.com`）；三个镜像均为第三方公益服务、与本应用无关；镜像方可见 IP 与所下版本，若文件被篡改会被 SHA-256 + 签名校验拦住安装。
- **其余页面同步瘦身**：升级弹窗（下载中/可安装/权限引导）、Token 用量 HUD、模型与思考级别面板、工具预设面板的描述均压到一行；校验失败与 401 等报错文案同步缩短，保留可操作部分。

---

## [1.7.4] - 2026-10-27

### 💬 会话正文不再空白、折叠卡片移除、上滑自动续批 (Release v1.7.4)

- **点开会话：顶部一张「已折叠 N 条早期记录」卡片 + 下面「正在等待模型…」+ 中间整片空白**：
  - **根因**：分页窗口是按**消息条数**硬切的（`messages.size - 50`）。一个回合里可能夹着几十条 `assistant`/`toolResult` 记录（长工具链），窗口起点于是掉进**最后一个回合的内部**；该回合的提问（`user` 消息）被切到窗口之外，`visibleTurns = allTurns.filter { userIndex >= windowStartIndex }` 直接算空 —— 列表里除了阶段提示什么都不剩。
    实测复现：`GET /api/sessions/<id>?tail=85` 返回 168 条记录，但 `user` 消息只有 index 25 这一条，`windowStartIndex = 118` → 可见回合数 **0**。
  - **修复**：窗口起点改为**向上对齐到回合起点**（`alignWindowToTurnStart`），并加兜底：即使起点算偏，也至少完整渲染最后一个回合，任何情况下都不再出现空白。
- **移除折叠大卡片，改成大厂式上滑续批**：
  - 顶部只留一行窄哨兵（`HistoryTopLoader`：未触发时一行浅灰提示，加载中是一枚 14dp 细圆圈），不再占据一屏、也不再需要点「前 N 条 / 展开全部」。
  - 滑到顶端（首行露出）**自动**加载上一批（`HISTORY_LOAD_BATCH = 50` 条，向上对齐回合），并用「行数补偿」把视口钉在原处，阅读位置不跳动；长回合把整批 50 条都包住时自动继续往前扩，不会自旋空转。
  - 首屏 `hasInitialScrolled` 之前不触发自动续批，避免刚进会话就连环加载、并把视口钉在顶部。
- **右侧时间轴（Minimap）跟着一起修正**：
  - 当前高亮档位改为直接读首个可见行的 key（`turn_<n>_…`）解析回合号 —— 旧实现用 `firstVisibleItemIndex` 减去横幅行数当回合下标猜，一旦回合折成「过程组 / 最终答复」多行或上滑插入新行，索引与回合号就对不上，导轨高亮乱跳。
  - 导轨/大纲的跳转偏移改用精确行数（`headerRowsFor()` + `turnRowCount()`，含提问前的系统消息行），不再按消息条数估算；「回到底部」同样用 `visibleRowCount()`。
  - 结论：上滑续批**不影响**时间轴 —— 时间轴数据源始终是完整的 `allTurns`，续批只改变渲染窗口。
- **顺带**：历史尚未到达时显示「正在同步会话历史…」占位（不再白屏）；起点横幅文案由「已完整展示全部 N 条记录」改为「本次已加载的起点 · 共 N 条记录」（原来的说法在服务端还有更早分页时是错的）。

---

## [1.7.3] - 2026-10-27

### 🔄 列表运行状态对齐 / API Key 真伪校验 / 设置文案精简 (Release v1.7.3)

- **“运行中”永远为 0、“已完成”永远等于总数（app 列表看不到 pi-web 正在跑的任务）**：
  - **根因**：v1.6.9 把 pi-web 的运行态从会话条目里的 `active` 字段改为解析 `GET /api/sessions` 顶层的 `runningSessionIds`，落盘到新字段 `isRunning`；但列表页所有判断仍读 `session.active`。而 pi-web 的会话条目已经**不再返回** `active`，它恒为 `false` —— 于是 `运行中(0)`、`已完成(总数)`，连卡片上的绿色 `运行中` 徽章、旋转圆弧、运行中会话的删除保护、常驻通知栏的“N 项运行”全都失效。
  - **修复**：`Session` 新增 `isLive = isRunning || active`（兼容 `runningSessionIds` 与本地新建会话的 `active = true`），`SessionListScreen` 与 `MainActivity` 的状态计数/筛选/排序/徽章/删除保护/通知栏统计全部改读 `isLive`。
  - **验证**：本机 `GET /api/sessions` 返回 `runningSessionIds` 含 3 个会话（经网关 4100 透传亦一致），修复后应显示 `运行中(2)`（当前工作区）并置顶。
- **列表时间看起来“比 pi-web 慢 8 小时”**：`modified` 是 UTC ISO 串，旧实现直接 `take(16)` 截字符串展示，等于把 UTC 当本地时间显示；现改为解析成时间戳后按本地时区格式化为 `MM-dd HH:mm`。
- **模型管理里「未配置 Key」是错的（pi-web 明明配好了）**：
  - **根因**：设置页只读 `models.json` 的 `providers.<p>.apiKey`，而 pi 的密钥其实存在 `~/.pi/agent/auth.json`，`models.json` 里通常**根本没有** `apiKey` 字段 —— 于是 pi-web 配好了、App 永远显示「未配置 Key」。
  - **修复**：新增 `GET /api/auth/providers` 解析（`apiKeyProviders[].configured/source`），状态显示改为「Key 已配置 · 已保存 / 环境变量 / models.json」，并按真实状态着色；`models.json` 的 `apiKey` 仅作兜底。
  - **实测**：本机网关返回 `{id: codywon, configured: true, source: "stored"}`，修复后不再误报未配置。
- **在 App 里配 Key 会把 pi-web 打成 `Error: 401 "Invalid API key"`**：
  - **根因**：`POST /api/auth/api-key/{provider}` 不做任何校验就把值写进 `auth.json`，此后 pi 的**每一个**请求都拿它当 Bearer Token —— 一次粘贴失误（粘了一半、粘错内容）就让 PC 上所有会话全部 401。本机 2026-10-27 16:35:28Z / 16:35:53Z / 16:37:15Z 三个会话的 401 就是这么来的。
  - **修复**：写入前先用 `POST /api/models-config/test` **真发一轮请求实测该候选 Key**，通过才落盘；被 401 拒绝则直接报错且绝不写入。实测关键坑：测试时必须把 providerName 换成一个一次性名字（`<provider>-key-verify`），否则 pi 的 `getAuth` 会优先取**已保存**凭据、把候选 Key 遮蔽掉，导致「错 Key 也测通过」；用临时名字后错 Key 0.2s 内返回 `401 "Invalid API key"`，对 Key 返回 `ok:true`。
  - **注意**：中转站的 Key 可能非常短、甚至与 Provider 同名（本机实际在用的就是一个 7 位自定义串），所以格式校验只拦「空值 / 含空格换行」，不做长度或形似规则，避免把合法 Key 拒之门外。
  - **逃生口**：Provider 卡片新增「清除」按钮（`DELETE /api/auth/api-key/{provider}`），录错 Key 后不必再上 PC 手改 `auth.json`。内置 Provider（无 Base URL，无法联网实测）需再点一次「跳过实测并保存」明示确认。
- **后台保活设置文案瘦身**：五项说明从两三行长句压成一行，如「常驻通知栏保活」→“通知栏常驻前台服务，进程不被后台回收”、“任务完成独立弹窗通知”→“完成时额外弹一张通知卡片”、“电池优化白名单”→“后台网络不受系统休眠限制”、“CPU 唤醒锁与断线自愈”→“任务期持有唤醒锁 + 断线自动重连”，分组说明也同步压缩。
- **顶栏移除“添加项目目录”图标**：列表页顶栏不再放 `CreateNewFolder` 按钮，避免误触；添加入口仍保留在工作区抽屉（横幅 `[+]` 与底部“打开 / 添加其他项目...”）。

---

## [1.7.2] - 2026-10-27

### ⚙️ 发布流水线现代化：Actions 全量升级 + 缓存 400 修复 (Release v1.7.2)

本次改动仅涉及 CI，App 功能与 v1.7.1 一致（仅版本号递增，用作流水线的端到端验证发布）。

- **Actions 全量升级到当前主版本（清掉 Node 20 弃用注解）**：
  - `actions/checkout@v4` → **v7**、`actions/setup-java@v4` → **v6**、`actions/upload-artifact@v4` → **v7**
  - `gradle/actions/setup-gradle@v3` → **v6**、`softprops/action-gh-release@v2` → **v3**
- **修复每次都出现的 `Failed to restore gradle-home-v1|…: Cache service responded with 400`**：
  - **根因**：`setup-gradle` 自 v4 起默认 `cache-provider: enhanced`，指向 `gradle-actions-caching` 商业预览服务；本仓库命中该服务时恢复阶段被返回 400（发布仍是绿的，但每次都挂一条红色注解）。
  - **修复**：显式 `cache-provider: basic`，走标准 `actions/cache` 后端（公共/私有仓库均免费），并显式 `cache-read-only: false`——tag 构建默认被当作「非默认分支」而只读不写，否则每次发版都要重新下载全部依赖；再加 `cache-cleanup: on-success` 保持缓存体积。
- **发布健壮性**：
  - 新增 `concurrency: release-${{ github.ref }}`，同一 tag 的重复触发串行执行，不再抢同一个 asset 名；
  - `upload-artifact` 加 `overwrite: true`、`action-gh-release` 加 `overwrite_files: true`，重跑同一个版本号不会因 `already_exists` 直接失败；
  - 新增 `timeout-minutes: 45` 防止 runner 卡死挂住发布队列。

---

## [1.7.1] - 2026-10-27

### 🔧 正文 Markdown 表格/行内代码真实修复，模型管理细节打磨 (Release v1.7.1)

- **GFM 表格被切碎、退化成普通段落（管道符原样显示）**：
  - **根因 1**：`TABLE_SEPARATOR_REGEX` 的 `\s*` 会吃掉表头行末的换行，把「表头行」和 `|---|` 之间插入一个空行，GFM 因此不再识别为表格。
    以「| | SaaS 退住记录 | … |」这类**首列为空**的三列表格为例，被改写后变成
    `表头行` + `空行` + `|---|`，整张表变成段落，`||----|----|----||` 原样码在正文里。
  - **根因 2**：`TABLE_STICKY_ROW_REGEX`（`\|\s*\|(?=\s*\w)`）会把同一行里的空单元格当行边界，把表头拆成两行。
  - **修复**：删掉上述两条全文正则，改为行级修复，只处理两种真实形变（行内 ` || ` 行边界、表头与分隔行粘在同一行），且引号块内的 `> | :--- | :--- |` 不再被拆掉引用前缀。
  - 回归校验（51 个会话、311 条含表格的 assistant 消息）：表格解析失败 4 → **0**。
- **行内代码被拆成“粉色大块”（正文里像未渲染）**：
  - **根因**：`healBrokenInlineBackticks` 在全文上直接跑 SQL 引号修复，会把**正常**的行内代码拆开——
    `` `WHERE p.del_flag='0' AND p.stay_hospital IN ('1','2')` `` 被改写成 `'WHERE … ('1','2')'`，
    反引号奇偶被打乱后，解析器只能把后面一大段正文当成 Code 节点。
  - **修复**：先用已成对的行内代码做分段保护，引号修复只作用于代码片段之外的自由文本；反引号奇偶异常行数 818 → **0**。
  - 行内代码样式收敛：去掉 `#F43F5E` 玫红前景与 `#0xFF2563EB` 蓝色，改等宽 + 轻底纹（中性灰）；超过 40 字符的行内代码不再加底纹，避免折行后形成大色块。
- **模型管理页细节**：
  - **Provider 数量徽标**：`CircleShape` 填充会因宽度不足被挤成竖椭圆深蓝块，改为等宽小字 `· 7`（无底色），选中态用主题色 75% 透明。
  - **API Key 入口**：卡片元信息行新增「配置 Key」胶囊，弹窗写入 pi 凭据库（`POST /api/auth/api-key/{provider}` → `auth.json`），不再只依赖 `models.json` 里那个对 pi 认证无效的 `apiKey` 字段；Provider 编辑弹窗里的同名字段重标为「API Key（可选，写入 models.json）」且**留空不再抹掉已有值**。
  - **按钮图标去重**：「+ 新增模型」同时有 Add 图标与字面 `+`，「从 API 发现模型」有 Bolt 图标与 `⚡` emoji；现统一为纯文字双列按钮。

---

## [1.7.0] - 2026-10-27

### 🧩 压缩上下文不再打断回合、摘要正文恢复 Markdown，MCP 与模型管理双页重设计 (Release v1.7.0)

- **「模型服务异常: This operation was aborted」根因定位与根治**：
  - **根因**：pi 的 `AgentSession.compact()` 第一行就是 `await this.abort()`，即**手动压缩天然会中止正在执行的回合**。App 侧有两处会发出该手动压缩：
    1. 上下文占用 ≥70% 时自动 `POST {"type":"compact"}`；
    2. 聊天页右上角「压缩上下文」按钮（无运行态判断）。
    两者之一在 Agent 正在出字或调工具时到达，就会把在途请求 abort 掉，pi 落一条 `stopReason: error` + `errorMessage: This operation was aborted`（`usage 0`、`durationMs 0`）的空 assistant 记录，回放时被渲染成刺眼的「⚠️ 模型服务异常」。已在真实会话文件 `.pi/agent/sessions/**` 中复现该签名：toolResult 之后 22ms 即出现该错误条目，26 秒后才写入 compaction 条目。
  - **移除 App 侧 70% 自动压缩**：阈值压缩交给 pi 自身的 `auto_compaction`（在回合边界触发、不打断在途请求）；App 不再与内核抢控制权。
  - **手动压缩增加运行态守门**：`isAgentBusy || isToolExecuting` 时不再发请求，改为提示「压缩会中断当前回合」；压缩失败也会兜底 Toast。
  - **中止类错误不再报「服务异常」**：历史回放时识别 `aborted` 系列错误，空正文条目直接跳过，有正文的降级为中性「⏸ 本轮生成已被中断」。
  - **上下文占用百分比纠正**：优先采用本地模型配置的 `contextWindow`，避免 1M 上下文模型被 200k 默认窗口算成 ~98% 满载。

- **压缩摘要 / 系统消息的 Markdown 渲染修复**：
  - **根因**：`ChatMessageItem` 的 `SYSTEM` 分支用的是纯 `Text(...)`，完全绕过了 `MarkdownRenderer`，因此 `## Goal`、`## Constraints & Preferences` 等标题标记原样裸露。
  - 文本正文统一改走 `MarkdownRenderer`，标题行单独提为卡片头部（剥除 `#/>/-` 等标记），压缩卡片展示「📦 … 查看摘要」，正文加分隔线后按 Markdown 渲染（标题、列表、表格、代码块全部生效）。
  - 只点标题行才展开/收起，避免选中摘要文字时误触发折叠。
  - `display: false` 的 `custom_message`（如 `auto-continue-after-compaction` 内部注入指令）不再当作系统消息展示，对齐 pi-web 的 `if (message.display)` 语义。

- **MCP 服务器卡片重设计**：
  - 「6 工具」徽标不再灰色填充、不再折行成「6 工/具」：工具数改为无底色等宽文字元信息，与传输协议/连接状态合成一行，`maxLines = 1, softWrap = false`。
  - 卡片改为三段式：① 状态点 + 名称（`weight(1f)` + 省略号）+ 开关；② 元信息 + 描述 + 地址；③ 分隔线下的等宽操作栏（测试 / 编辑 / 移除），操作按钮独占一行，任何屏幕宽度都不再挤压。

- **模型管理页布局与配色重设计**：
  - **思考档位选择器**：去掉按字符长度手工配权的实现（曾导致 `minimal` 被压成 `minima+l`、留白突变），改为等内边距胶囊 + 横向滚动，单行绝不折行；标题右侧的重复档位徽标替换为「新会话生效」说明。
  - **Provider 卡片头部**：名称占满剩余宽度并省略，编辑/删除收敛为 30dp 圆形图标按钮；协议胶囊与 Key 状态下移为独立元信息行，消除窄屏重叠。
  - **模型调校卡片头部**：标题 + 图标操作（测试 / 移除），模型 ID 单独一行等宽展示，彻底消除「移/除」被挤成竖排的畸形按钮。
  - **能力开关**：两个长中英双标签 Checkbox 改为双列等宽等高的 `深度思考 / 多模态视觉` 开关胶囊，窄屏不折行。
  - **配色收敛**：移除 `#8B5CF6` 紫、`#0284C7` 天蓝、`#10B981/#059669` 绿、`#EF5350/#EF4444/#991B1B` 多套红色，统一为「主题 primary + 中性灰 + `colorScheme.error`」三档；「思考 / 视觉」标签改为 primary 强调 + 中性弱化两档；去掉重复的 `⚡` emoji（已有 Bolt 图标）。

---

## [1.6.9] - 2026-10-27

### ⚡ 工作区会话极速秒开、运行态精准解耦与正文渲染/UI深度加固 (Release v1.6.9)
- **彻底根治大体积会话进入白屏与误显「正在等待模型...」缺陷**：
  - **运行态与活跃状态精准解耦**：彻底剥离服务端 `session.active`（仅标识 Web 当前选中项，不代表 Agent 运行）对运行态的污染，改为严格基于 `session.isRunning` 与 SSE `isStreaming` 判定，进入空闲会话不再出现虚假「正在等待模型...」及常驻停止按钮；
  - **内存秒开缓存 (`sessionHistoryCache`)**：进入历史会话时优先极速还原内存快照，正文与模型配置瞬间上屏，彻底消除 1M 带宽下的漫长白屏；
  - **首屏分段轻量极速加载 (`tail = 60`)**：首屏采用轻量窗口极速拉取并提供智能会话延伸，杜绝在弱网环境下同步发起十多次向上翻页拉取，超长工具输出实施 12,000 字符安全截断，秒开顺滑；
  - **`stats.cost` 类型安全兼容解析**：完美兼容数值与 `{ input, output, total }` 对象结构，彻底解决 `UnsupportedOperationException` 导致的解析崩溃与正文被清空问题；
  - **SSE 状态闭环**：收到 `connected` 事件且 `isStreaming == false` 时自动复位空闲状态，`auto_compaction_end` 独立闭环并自动触发消息快照刷新。
- **Markdown 正文渲染深度修复**：
  - **反引号保护与智能自愈**：`CjkMarkdownNormalizer` 仅保护三反引号围栏代码块，新增 `healBrokenInlineBackticks` 智能修复 SQL/伪代码中混写的单引号/反引号及跨中文句逗孤立反引号，彻底杜绝整段正文被误识别为单行红色代码块；
  - **移除粗体有损拆散正则**：移除在标点符号强插空格的正则，防止 `** (` 裸露破坏排版，并增加孤立不成对粗体兜底清理；
  - **GFM 表格无缝成块**：表格紧贴上方普通文本时自动补充空行以正确触发 CommonMark `TableBlock` 结构化表格解析。
- **提供商与模型管理 UI 布局细节加固**：
  - **思考档位单行完整居中**：根据字符长度为 7 个档位动态分配权重比例，结合 `maxLines = 1, softWrap = false`，彻底消除 `minimal` 档位折行成 `minima + l`；
  - **「从 API 发现模型」与「+ 新增模型」重构**：重构为独立的一行双列均衡按钮栏，彻底消除窄屏下右侧被压缩成细长竖向灰条的视觉 Bug；
  - **卡片按钮防折叠与列表底部安全边距**：Provider 顶栏操作按钮加固单行水平排列，列表底部安全内边距扩展至 `96.dp`，避免被系统手势或底部导航裁剪。

---

## [1.6.8] - 2026-10-27

### 🚀 稳定性加固、编译打包与全链路安全版本发布 (Release v1.6.8)
- **Minimap 提问时间轴导轨与侧边贴边抽屉交互全面升级**：
  - 导轨上的每个方块节点均拥有独立的触控热区，轻触任意方块直接平滑滚动定位到该轮提问，彻底告别误触弹窗；
  - 抽屉升级为移动端右侧贴边全高抽屉，搭配优雅半透明暗色遮罩（Scrim），轻触背景或条目顺滑收起并精准定位；
  - 彻底根治“更早的 1 轮还没加载”误报逻辑，提供主视图一键展开全量历史入口；
- **状态持久化与抗回收能力全面增强**：
  - 输入框草稿按会话 ID 自动记忆，横竖屏旋转或内存回收不丢输入内容；
  - 当前会话与历史分页窗口改用规范的 `SessionSaver` 与 `rememberSaveable`，进程重建后不再异常返回首页；
- **Markdown 流式渲染性能提速**：
  - 解析器单例化 + 块级解析缓存，长文本与连续工具输出流畅无卡顿；
- **全链路安全防护与签名加固**：
  - 发布包签名密钥移入 GitHub Secrets 闭环管理，构建安全可控；
  - 应用内更新强制校验 SHA-256 完整性清单与系统签名证书指纹。

---

## [1.6.7] - 2026-10-27

### ⚡ 性能优化、状态持久化与安全更新闭环 (Performance, Persistence & Security)
- **草稿持久化与状态抗杀能力**：
  - 输入框草稿按会话 ID 自动记忆与防丢，切换会话或 Activity 重建（如旋转屏幕、系统回收内存）输入文字永不丢失；
  - 会话当前选中态与历史消息分页窗口改用 `rememberSaveable`，进程重建后不再异常被打回会话列表首页；
- **Markdown 流式渲染性能提速**：
  - Markdown 解析器单例化 + 引入块级缓存（Block-level cache），彻底消除流式打字与大消息渲染时的 $O(n^2)$ 重复解析开销，长文本滚动更加丝滑流畅；
- **安装更新链双重校验安全闭环**：
  - 生产发布签名密钥移入 GitHub Secrets 保管，Release 构建严格验证；
  - 应用内更新链路强制校验发布包 SHA-256 完整性清单与签名证书指纹，双重闸门防篡改；
- **网络层排障可观测性增强**：
  - 网络层回退降级逻辑不再静默吞掉真实网络异常，错误日志清晰留痕；
  - 下载看门狗与调优参数具名化，重试策略更加稳健。

---

## [1.6.6] - 2026-10-27

### 🧭 提问时间轴导轨与侧边大纲抽屉交互重构 (Minimap Rail & Slide Drawer)
- **Minimap 导轨方块小点独立响应直接跳转 (1:1 对标 pi-web)**：
  - 彻底解耦“节点点击定位”与“抽屉呼出”两大功能；
  - 导轨上的每个方块节点均拥有独立的触控热区，轻触任意方块直接平滑滚动定位到该轮提问，**绝不再误触发弹窗或抽屉**；
  - 视口当前所在的活动轮次具有 Primary 蓝高亮、精致描边与尺寸微放大指示；
- **抽屉形态升级为移动端右侧贴边全高抽屉 (Slide-in Right Drawer)**：
  - **彻底告别四边悬空、挡死主界面的怪异居中弹窗**！
  - 采用标准移动端侧滑抽屉设计（贴合屏幕右边缘，占宽 82%，左侧露出 18% 聊天背景并覆盖半透明暗色遮罩 Scrim）；
  - 点击左侧暗色遮罩、向右滑动、点击关闭叉号或点击抽屉内任一轮次/助手小标题，均会自动顺滑收起抽屉并精准定位；
  - 导轨最底部常驻微型时间轴图标按钮（`Icons.Default.History`），作为专属抽屉开关；
- **彻底根治“更早的 1 轮还没加载”虚假提示与机制缺陷**：
  - 修正了在全量轮次已在时间轴中呈现时误计算并展示“更早轮次未加载”的逻辑缺陷；
  - 当主界面存在长消息折叠时，在抽屉顶部提供直观的“在主聊天视图中展开全部历史”操作，一键无缝拉通全量记录；
- **回到底部悬浮按钮排版避让**：
  - 将正文框右下角回到底部悬浮按钮向左避让 34dp，彻底消除与右侧时间轴导轨的重叠遮挡。

---

## [1.6.5] - 2026-10-26

### 🎨 全局设置与模型管理页面视觉与交互全面重构 (Clean & Elegant UI Redesign)
- **彻底告别框套框与视觉杂乱堆砌**：
  - 采用现代化流线型分组卡片（Grouped Inset Cards），统一 12dp 柔和圆角与 0.7dp 超浅边框，大幅降噪；
- **默认推理思考档位重构为分段胶囊选择器 (Segmented Pills)**：
  - 告别粗苯的横向单选大框，采用紧凑现代的 Segmented Control，高度降低 45%，释放首屏宝贵纵向高度；
- **提供商卡片与操作收敛**：
  - 提供商名称、API 协议与操作合并为单行；
  - 彻底清除之前一屏之内出现多达 3 个重复、不同颜色的“发现/导入模型”混乱按钮，统一收敛为干净的 `[⚡ 从 API 发现模型]` 与 `[+ 添加模型]`；
  - Base URL 采用轻量卡片完整呈现，轻触整行即可一键复制；
- **下属模型列表升级为全宽单列优雅清单**：
  - **彻底告别截断模型全名的 2 列网格**！长模型名（如 `claude-opus-4-6-thinking`、`gemini-3.8-flash-high`）完整 Monospace 排版展示，绝不截断；
  - 每行清晰展示模型 ID、上下文窗口与输出规格注脚，右侧点缀克制的 `[思考]` 与 `[视觉]` 徽章；
  - 当前选中模型带有专属高光与蓝色对勾，支持点击平滑展开/收起；
- **模型参数调校面板层次梳理**：
  - 基础信息（ID、Name）、一键智能匹配规格、能力开关、规格上限清晰分组；
  - 价格与高级 API 覆盖默认优雅收起在折叠面板中，保持主屏干净整齐；
- **底部悬浮主操作栏**：
  - 浮动毛玻璃半透明保存大药丸按钮，滑动时始终跟随，操作丝滑顺手。

---

## [1.6.4] - 2026-10-26

### 🚀 1:1 复刻 pi-web 每轮执行过程折叠面板与历次提问大纲抽屉 (ProcessDetailsGroup & ChatMinimap)
- **1:1 源码级复刻每轮过程总结与折叠面板 (`ProcessDetailsGroup`)**：
  - 对标 `pi-web` 的 `ChatWindow.tsx:197-245` 与 `lib/turn-duration.ts` 规范；
  - **格式规范**：`> 4 条消息 · 4 次工具调用 · 耗时 2m 13s`；
  - **耗时算法**：`<60s` 展示秒（如 `14s`），`1~59m` 展示分秒（如 `2m 13s`），`>=60m` 展示时分秒（如 `1h 2m 5s`）；
  - **状态机与动画**：正在运行中或未产出最终回答时，中间过程（工具调用、深度思考）默认展开实时呈现；回答产出后默认自动折叠为紧凑优雅的一行小字；用户可轻触左侧箭头随时自由展开/收起全部工具详情，箭头带丝滑 90° 旋转动画；
  - 中间过程卡片展开后完整保留工具参数、运行结果与长按拖选复制体验。
- **1:1 源码级复刻右侧时间轴导轨与历次提问大纲抽屉 (`ChatMinimap`)**：
  - 对标 `pi-web` 的 `ChatMinimap.tsx` 与 `ChatMinimap.module.css`；
  - **右侧 28dp 竖向细导轨**：带有浅色中心轴线与方块刻度点（高亮当前视口所在轮次）；底部配备锁定/解锁开关按钮，支持点击常驻锁定或随点随关；
  - **历次提问大纲抽屉 (从右向左优雅滑出)**：
    - **分页无缝联动**：顶部支持“`更早的 X 轮还没加载，点击加载`”，与现有分页窗口深度绑定；
    - **左列 36dp 序号区**：展示双位数轮次编号（如 `01`, `02`, `19`）与工具调用次数小胶囊气泡（如 `4`, `6`）；
    - **右列用户提问与助手答复大纲**：
      - 用户输入文本预览（最多 3 行，点击平滑滚动跳转至对应提问）；
      - 助手关键标题大纲提取器（`extractAssistantOutline`），带有醒目的方形主色 `A` 标识（如 `A 处理结果`、`A 一个提醒`），轻触可一键直达助手回答。
- **1:1 对标 pi-web 纯净图片预览 (移除突兀的右下角黑色角标)**：
  - 彻底移除缩略图右下角覆盖原图的黑色分辨率文字角标；
  - 采用对标 pi-web 的 8dp 圆角与精致微描边，轻触整张图片即可呼出沉浸式大图查看器 (Lightbox)，支持双指无级缩放与平移拖拽，干净大气；
- **彻底根治已删除模型/供应商 (如 CloseAI) 依然在下拉框复活的幽灵缓存 Bug**：
  - 清除 `PiApiClient` 中历史遗留的硬编码假模型，避免网络异常时 fallback 注入孤儿模型；
  - 重构模型覆盖算法：严格以服务端当前的合法 Providers 集合为准，不再复活服务端已不存在的孤儿模型；
  - 一旦发现本地 SharedPreferences 缓存包含已删除的 Provider，自动识别并彻底清除；
  - 在模型管理大盘中删除 Provider 或模型时，同步清理本地持久化条目，确保所见即所得。
- **双轨架构严格 100% 同步**：
  - 核心组件与业务页面在 `app/` 与 `android/app/` 两条路径保持绝对镜像一致。

---

## [1.6.3] - 2026-10-26

### 🌟 会话长按自由拖选复制、工作区抽屉布局重构与项目长按维护大盘 (Text Selection & Project Management)
- **会话消息原生文本选择与操作全覆盖 (`SelectionContainer`)**：
  - 聊天正文与 Markdown 渲染引擎全面挂载 `SelectionContainer`；
  - 支持手机端长按呼出 Android 原生双向水滴光标滑块，可随心所欲自由滑动选定任意字句、列表或代码片段；
  - 自动呼出原生浮动文本菜单栏（【全选】、【复制】、【分享】、【网络搜索】）；
  - 深度思考卡片（Thinking Card）展开后同样支持长按选取文本，满足复杂技术推导的任意摘抄诉求；
  - 一键复制按钮与顶栏操作使用 `DisableSelection` 隔离保护，手势体验流畅无冲突；
- **工作区抽屉布局全面优化 (释放首屏宝贵高度)**：
  - 彻底移除原抽屉顶部占用近 55dp 高度的醒目实心大按钮，首屏直接呈现“常用置顶”与核心活跃项目；
  - **顶栏轻量集成**：在“工作区项目”标题右侧并排常驻小巧精致的 `+` 图标按钮，随时触手可及；
  - **底部优雅卡片**：1:1 对标 pi-web 原生规范，在项目列表最底部提供轻量级的【打开 / 添加其他项目...】卡片；
- **1:1 源码级复刻 pi-web 工作区项目长按维护菜单 (ModalBottomSheet)**：
  - 在抽屉中对任意项目支持**单击切换、长按或轻触右侧 `⋮` 更多按钮**，平滑滑出项目管理大盘；
  - **项目核心属性**：
    - 📌 **置顶项目 / 取消置顶**：置顶项目常驻大盘前列，配备专属琥珀金图钉标识；
    - ✏️ **重命名项目别名...**：支持为工作区设置易读的自定义友好别名，并支持一键恢复默认名称；
    - ↗️ **在新会话中打开**：切换至该工作区并直接创建新任务会话；
  - **大盘排序微调**：
    - ⌃ **上移项目** / ⌄ **下移项目**：自定义调整工作区列表排列顺序；
  - **会话维护与安全清理**：
    - 🗄️ **归档 7 天前的会话 · N**：一键批量沉淀已逾一周的历史老会话，保持会话大盘清爽；
    - 🗄️ **查看已归档会话 · N**：弹窗列表查看该项目下已归档的会话，并支持一键恢复；
    - 🗑️ **删除全部会话... · N**：带二次危险确认弹窗，严格对标 pi-web（自动保留正在运行的活跃会话，安全删除历史会话）；
- **App 健壮性增强：上游模型错误即时捕获与警告卡片渲染**：
  - 彻底根除此前上游端点异常中断（如 HTTP 422 `developer` 角色拒收、429 配额用尽等）时界面静默发呆的问题；
  - 在会话流与历史回溯中增加深度错误解析，一旦上游异常，即刻在界面渲染高对比度警告卡片（`⚠️ 模型服务异常: 422 ...`），消除状态盲区。

---

## [1.6.2] - 2026-10-26

### 🚀 模型供应商 Base URL 完整展示、下属模型网格折叠与 API 自动发现批量添加 (Provider Management & Discover API)
- **模型供应商 Base URL 完整展示与一键快捷复制**：
  - 彻底解决此前单行被 `TextOverflow.Ellipsis` 粗暴截断无法看清后面路径的问题；
  - 采用极客工业风 Surface 独立展示框，完整显示 Base URL 全貌，未配置时贴心提示“未指定自定义 Base URL，采用接口默认端点”；
  - 提供快捷复制按钮，轻触即可将 Base URL 拷贝至剪贴板；
- **下属模型列表交互大盘全面升级 (自适应 2 列精致网格 + 折叠收起)**：
  - 告别此前单行横向拖拽（添加按钮被丢在队伍最末端）的不便交互；
  - 顶部常驻快捷双操作：【⚡ 从 API 导入】与【+ 添加模型】，触手可及；
  - 下方排布为移动端自适应 2 列芯片网格，清晰呈现每个模型的 ID 与推理思考/多模态视觉小图标；
  - 超过 6 个模型时支持一键优雅折叠/展开，既保留大盘掌控力，又避免占满整个页面；
- **1:1 源码级复刻 pi-web 模型自动发现功能 (`POST /api/models-config/discover` & `/v1/models`)**：
  - 接入服务端 `POST /api/models-config/discover` 端点，上游自动向提供商端点拉取模型列表；
  - 同时支持客户端直连 `/v1/models` 或 `/models` 作为高可用 Fallback；
  - 弹出精致的 API 发现模型管理窗：
    - 支持即时搜索过滤拉取到的模型；
    - 智能排重：已添加的模型自动置灰并标注“已添加”徽章；
    - 支持“全选未添加”与勾选多选；
    - 一键批量导入至当前 Provider，并智能推断推理与视觉特性，保存即刻生效。

---

## [1.6.1] - 2026-10-26

### 🌟 默认模型与星标选择机制 1:1 源码级复刻 (Default Model Selection & PUT /api/models/default)
- **打通服务端默认模型配置接口 (`PUT /api/models/default`)**：
  - 1:1 对标 pi-web 原生协议，客户端一键设置默认模型与默认 Provider，持久化同步至 PC 端 `~/.pi/agent/settings.json`；
  - 动态拉取模型列表时自动同步服务端当前默认模型与默认思考档位（`defaultModel` 与 `defaultThinkingLevel`）；
- **模型与思考切换面板交互全方位对标**：
  - **顶部新增“筛选模型...”即时搜索栏**：支持按模型名称、ID、Provider 进行即时模糊搜索过滤；
  - **星标收藏/设为默认模型系统**：
    - 当前默认模型高亮展示**琥珀金实心五角星 ★** 与专属“默认”徽章；
    - 其它模型展示轻量**空心五角星 ☆**；
    - 点击五角星：立即向网关发送 `PUT /api/models/default`，将该模型设为全局默认模型，新创建的任务会话将自动默认采用此模型；
  - **双重状态清晰分离**：
    - 左侧勾选 `✓`：代表当前会话正在使用的模型；
    - 右侧星标 `★`：代表全局默认模型。

---

## [1.6.0] - 2026-10-26

### 🎨 全局设置与模型管理 UI 体验全维度重构 (Model Management UI & Design Refactoring)
- **下属模型列表徽标优雅重构**：
  - 彻底移除原先深蓝黑死色的竖条大写 `T` 标签，改为柔和科技紫 `Psychology`（推理思考大脑图标）与微型 `Image`（视觉输入图标），与模型名称自然和谐对齐；
- **解决输入框巨型方块问题 (高度压缩与自适应)**：
  - 研发推出专用极客表单微输入框 `ModelInputField`，高度严格控制在 `36dp`，标签独立置顶，彻底取代 Material 3 默认 56dp 撑爆排版的大框；
  - 模型 `ID *` 与 `Name` 横向等宽自适应分布，垂直居中排布，呼吸感大幅提升；
- **测试通过状态通知条现代设计**：
  - 废弃浅色模式下对比度极差且发灰发脏的浅绿底浅绿字；
  - 采用现代微通知规范：清爽薄荷绿背景（`alpha = 0.12f`）、精致细边框、高对比度沉稳深翠绿文字与 `CheckCircle` 图标，直观呈现测试通过与具体毫秒延迟（失败则呈现清晰绯红 `ErrorOutline` 提示）；
- **模型规格与百万 Token 价格 2x2 网格排版**：
  - “模型规格”（上下文窗口与最大输出 tokens）采用紧凑双列单行排布，带 `tokens` 单位后缀；
  - “每百万 Tokens 价格”彻底告别单行硬挤 4 个输入框导致的正方形大方坑，重构为 **2x2 优雅网格**（输入/输出为第一行，缓存读/缓存写为第二行），带 `$` 前缀与 `/M` 后缀，宽度充裕舒展；
- **高级设置默认折叠收起**：
  - `isAdvancedExpanded` 默认置为 `false`，界面首屏清爽简洁；
  - 封装为精致的设置卡片，右侧带有当前生效特性摘要微标（如“思考等级: 默认 · API: 默认”），点击平滑展开；
- **思考等级映射 (Custom) 紧凑对齐**：
  - 当激活 Custom 映射时，右侧自定义值输入框高度严格与左侧 Segmented Buttons 等高（`26dp`），居中内嵌，彻底根除撑高整行的突兀大白框。

---

## [1.5.9] - 2026-10-26

### 🎨 会话窗口底栏排版与全面屏适配体验优化 (Bottom Bar Metrics & Curved Screen Optimization)
- **移除底栏重复的压缩按钮**：
  - 输入框上方已具备专属且功能完善的上下文压缩按钮（带 70% 阈值亮黄预警与转圈动效），彻底移除底部状态栏右侧重复的 `[* 压缩]` 胶囊组件，释放出近 80dp 的横向排版空间；
- **扩大全面屏左右安全边距（20dp）**：
  - 将底部栏水平 padding 从 `14dp` 拓展至 `20dp`，从根源上杜绝现代 Android 手机物理大圆角屏幕和曲面屏对左侧数据指标（如上下文 `DataUsage` 图标）的裁切遮挡；
- **4 核心指标匀称呼吸感排布**：
  - 采用 `Arrangement.SpaceBetween` 左右通透排布 4 个关键数据指标：
    1. **上下文占用**：`Icons.Default.DataUsage` + `$percent%`（超 70% 警示琥珀黄、超 90% 高危珊瑚红）；
    2. **输入 Tokens**：`↑ $inTokens in`（箭头弱化辅助对比）；
    3. **输出 Tokens**：`↓ $outTokens out`；
    4. **缓存读取量**：`Icons.Default.Bolt` + `$cacheTokensStr`（如 `58.9k cache R`，天蓝色闪电高亮）；
  - 底部指标整条可点击一键唤醒 Token HUD 对话框，查看全面详细的统计大盘。

---

## [1.5.8] - 2026-10-26

### 📊 通知中心与大盘体验重构 (Smart Foreground Notification & Task Counter)
- **常驻通知栏优雅加入多维任务统计大盘**：
  - **运行中态**：标题展示 `⚡ 智能任务执行中 · [任务名称]`，副文本高亮展示 `[X 项运行 / Y 项就绪]` 实时统计，内容直观呈现当前推理/工具执行阶段；
  - **刚完成态**：任务完成时立即在常驻通知栏更新为 `✓ 任务已完成 · [任务名称]`，副文本展示 `0 运行 / Y 完成`；
  - **空闲守护态**：展示 `piDroid 守护中 · [Y 项任务就绪]` 与主机连接状态，随时响应远程指令；
  - **大文本折叠卡片 (`BigTextStyle`)**：下拉展开常驻通知时，优雅呈现当前任务、所属工作区、实时阶段、整体统计及网关连接大盘；
  - **即时静默刷新机制**：利用 `NotificationManager.notify` 对正在运行的前台服务进行毫秒级无闪烁热更新；
  - **会话定向唤醒**：点击常驻通知卡片可直通当前正在运行或指定的会话详情。

### 🔕 彻底治理无名盲盒通知堆积 (Eliminate Vague Notifications)
- **废除无标题任务完成盲盒通知**：彻底清理以往仅写着“任务已完成 / 所有步骤已执行完毕”却不知是何任务的冗余通知，避免通知栏堆积垃圾；
- **任务感知完全合流至三维感官**：
  - 任务完成时，由原生 PCM 双音合成器悦耳“叮咚”音效 + 清脆马达双振脉冲 + 常驻通知栏顶部大盘实时协同告知；
- **系统设置新增定制开关**：
  - 在【系统设置】->【后台保活】中加入【任务完成独立弹窗通知】开关，默认关闭；
  - 若用户按需开启，弹出的通知**必定精确携带具体任务标题与项目名称**，点击可直接切回该会话，绝不再出现无名盲盒通知。

---

## [1.5.7] - 2026-10-26

### 🛠️ 编译与核心稳定性修复 (Build & Stability Fixes)
- **修复触觉与通知辅助模块语法闭合**：彻底修复 `PiHapticsAndNotify.kt` 中的语法破坏与未闭合注释，确保马达双振与原生 PCM 提示音及常驻通知服务平稳运行；
- **修正 Compose 布局属性对齐类型**：修正 `GlobalModelsConfigTab.kt` 中 `Column` 布局误用 `Alignment.CenterVertically` 引发的构建类型不匹配，保障模型配置树与 Provider 管理流畅加载；
- **修正分支任务切换逻辑**：在消息分叉新会话操作中无缝衔接 `openSessionWithHistory`，自动完成全场景增量对账与 SSE 状态监听。

### ⚡ 技能、MCP 协议与插件扩展 1:1 源码级复刻 (Skills, MCP & Plugins Complete Suite)
- **MCP 节点即时连通性探测 (POST /api/mcp/test)**：
  - 在每个 MCP 节点操作栏新增【⚡ 测试】按钮，带异步转圈加载与防重复点击机制；
  - 连通成功后弹出工具发现面板，直观呈现节点健康状态与已导出的具体工具列表明细（如 `bash`, `fetch`, `read_file` 等）；若连通失败则高亮展示网关错误原因；
- **MCP 环境变量 (env) 与作用域完备支持**：
  - 支持配置全局 (Global) 与项目级 (Project) MCP 服务；
  - 增加对环境变量键值对及参数的完备读写支持；
- **技能中心 (Skills) 休眠与在线编辑对标**：
  - 完整对标 `orderSkillsByDormancy` 与模型调用禁用休眠开关；
  - 支持直接在线查看并编辑 `SKILL.md`，即时同步至网关本地文件系统；
- **插件扩展 (Plugins) 深度集成**：
  - 完整展示扩展包（NPM / Git / Local）及其资源徽章统计（Extensions, Skills, Prompts, Themes），支持一键启用、禁用、更新与卸载。

---

## [1.5.6] - 2026-10-26

### 🔄 会话与排队撤回体验深度复刻 (Session & Queue Revert)
- **排队消息撤回原样恢复输入框**：运行中下发排队消息（Follow-up / Steer）点击【撤回 ↺】后，不再直接丢失清空，而是完整将文字与图片附件原封不动恢复到输入框中，支持修改润色后重新发送；
- **用户消息气泡操作全家桶 (1:1 对标 pi-web)**：
  - **`📋 复制`**：一键将当前提问内容精准复制到系统剪贴板；
  - **`↳ 从此处编辑`**：将历史提问内容一键填入底部输入框并聚焦，便于以此为基础分叉新提问；
  - **`🔀 新会话`**：调用 `POST /api/sessions/[id]/fork`，以此处节点为上下文独立创建全新分支会话并自动切换进入；
  - **时间戳**：精准呈现消息发送时间（如 `14:17`）。

### ⚡ 底部状态栏与 70% 自动压缩机制 (Auto-Compaction & Token Layout)
- **缓存 Token 自动换算与突出呈现**：在底部指标栏中将缓存读取 Token 独立高亮呈现（如 `58.9k cache R`），按单位自动精细换算，一眼掌握 Prompt 缓存加速效果；
- **右侧常驻 `* 压缩` 快捷按钮**：右侧集成紧凑的上下文压缩按钮，当上下文占用达到 70% 时自动呈现琥珀色高亮提醒；
- **70% 上下文自动压缩 (Auto-Compaction)**：严格对标 `pi-web` 机制，当当前会话上下文使用率跨过 70% 阈值时自动触发上下文智能压缩，防范窗口溢出。

### 🧩 技能 (Skills)、MCP 协议与插件扩展 (Plugins) 对标
- **MCP 节点连通性测试**：接入 `POST /api/mcp/test`，支持在 MCP 设置中一键测试 Stdio / SSE 节点的健康状态；
- **技能包与插件深度集成**：完整支持技能别名自定义、`SKILL.md` 在线源码编辑、作用域（全局/项目）区分，以及插件 NPM/Git 在线安装管理。

### 🛡 代码评审修掉的两个真问题

**一、全屏看图会 OOM（高危）**：卡片解码早就在用 `decodeSampledImage` 降采样，但
`FullscreenMediaLightboxDialog` 里仍是全尺寸 `BitmapFactory.decodeByteArray` —— 一张
4000×3000 的 CAD 出图约占 48MB 堆，正是这个仓库赔过一次的「一打开大图就闪退」。
现在查看器按屏幕长边 ×2（`MediaLoadPolicy` 的既有口径）降采样，缩放到 6× 仍够看清。

**二、泄露密钥的护栏会静默失效（安全）**：`Refuse the compromised keystore` 原来只在
「解析出指纹且**正好等于**泄露那把」时才红。keytool 失败、密码错、输出格式变化或大小写不同，
`FP` 就是空的或对不上，护栏照样放行。现在指纹解析不出来直接让流水线失败（失败即关闭），
比较前两边都归一化成小写去分隔符；顺带把取指纹的方式从 `awk -F': '` 换成对空格更宽容的 `sed`。

### 🧹 评审里顺手修的小问题

- 工具定义面板：`LazyColumn` 的 key 用工具名，服务端一旦给重名就会抛
  `IllegalArgumentException`（整块白屏），改为先 `distinctBy`；卡片底色从 `surfaceContainer`
  换成 `background` —— 本行参数用的是 `textMuted`，而 `ColorContrastTest` 只把
  「textMuted 画在 background / surfaceContainerLow」算达标（3.72:1），换到
  `surfaceContainer` 会掉到 3.3–3.4:1。
- 工具卡的策略拒绝提示改用 `remember(tool.isError, tool.output)` 缓存：`explain()` 是几次全文
  扫描，几十 KB 的工具输出在流式期间每帧重算一次并不便宜。
- 工具定义面板在会话消失（新建对话/会话被重建）时自动关闭，不再留在「已开启」状态等下一个
  会话出现时突然弹出。
