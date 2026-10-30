# 设计决策：会话模型身份 (provider + modelId) 的同步

> 状态：**已决策（A 方案已上线）**；B/C 方案**记录在案、暂不实施**。
> 相关版本：1.7.57 → 1.7.62。

## 1. 问题

一个 `modelId` 可以同时挂在多个 provider 下（实测本机目录：`deepseek-flash` 同时属于
`codywon` 与 `deepseek`，前者显示名 `deepseek-flash`、后者 `DeepSeek V4.1 Flash`）。
凡是「是不是同一个模型」的判断只比 id，就会出现：

- 选择器两行都是 ✓，切了供应商界面像素级不变（1.7.57 修）；
- models.json / 本地覆盖按 id 兜底，B 供应商的参数写到 A 供应商的同名模型上（1.7.57 修）；
- 回到会话时被**切换之前**的本地快照或全局默认覆盖成上一家（1.7.60 修）；
- PC 端切的模型手机端长时间跟不上（1.7.62 修）。

## 2. 事实（实测，2026-10-30，pi-web 0.12.2 / SDK 1.4.0）

| 观察 | 结论 |
|---|---|
| `POST /api/agent/{id} {"type":"set_model",…}` 后 `/context`、`/state`、`get_state` 立即变 | 服务端从不「回弹」，切换真实生效 |
| 同一次切换的 SSE 事件流只有 `connected` | **pi-web/SDK 不发模型变更事件** |
| 切思考档的 SSE 有 `thinking_level_changed` | 对照：思考档有事件，模型没有 |
| SDK `agent-session.js` `setThinkingLevel()` 有 `_emit({type:"thinking_level_changed"})`；`setModel()` 只有 `appendModelChange()`（只落盘）+ `_emitModelSelect()`（仅扩展事件） | 缺口在 **SDK**，不在 pi-web |
| pi-web SSE mapper（`.next/server/chunks/6429.js` 模块 42912 的 `E$`）只排除 `turn_start`/`turn_end`/`entry_appended` 与 system 消息，其余原样返回 | 新增事件类型**无需改 pi-web** |

体积实测（决定探针选型）：

| 端点 | 体积 | 含模型身份 |
|---|---|---|
| `GET /api/sessions/{id}?tail=60&deferMedia=1&tree=summary` | 267 KB | ✅ |
| `GET /api/sessions/{id}/state` | 25 KB | ✅ |
| `GET /api/sessions/{id}/context?tail=1` | **1.9 KB** | ✅ |
| `GET /api/models` | 2.9 KB | 目录 |
| `GET /api/agent/running` | 0.23 KB | ❌ |

## 3. 当前实现（A 方案，已上线）

- **身份判定**：`SessionModelSelection.matches/isSelected/isProviderAmbiguous`，provider + id 都参与。
- **本地快照不再写模型**：`applyHistoryMeta` 只回放正文/统计/游标；模型身份只来自
  本机刚切的选择（`SessionModelIdentityCodec`，按会话持久化，上限 300 条）或服务端网络快照。
- **PC 端切换的跟进**：同步循环每轮（空闲 3s / 在跑 1.5s）在「不需要全量快照」的那一拍拉一次
  `context?tail=1`（1.9 KB），模型或思考档变了就更新并落盘。
- **面板打开即对账**：`modelsOnly` 拉目录（2.9 KB）+ 探针（1.9 KB）。
- 护栏：`modelMetaRevision` + `pendingModelMetaChanges`，本机刚切的选择优先于在途请求。
- 顺带加固：同步循环每轮 try/catch（`CancellationException` 仍上抛），单次异常不再弄死轮询。

延迟：PC 切完 → 手机 **≤3s** 跟上；代价 1.9 KB / 3s。

## 4. B 方案（本地给 SDK 打补丁）——**记录，暂不实施**

目标：把 3s 降到瞬时。

### 4.1 改什么

仅 SDK 一处（`@agegr/pi-web` 嵌套依赖）：

```
C:\Users\29092\AppData\Roaming\npm\node_modules\@agegr\pi-web\node_modules\
  @earendil-works\pi-coding-agent\dist\core\agent-session.js
```

`setModel(model, options)`（1.4.0 版第 2008~2026 行）在 `_emitModelSelect` 之前补一行：

```js
this._emit({ type: "model_changed", provider: model.provider, modelId: model.id });
```

锚点建议用 `this.sessionManager.appendModelChange(model.provider, model.id);`（唯一）。
理想再同步 `dist/core/agent-session.d.ts` 的 `AgentSessionEvent` 联合类型（运行时无影响）。

pi-web 侧 **0 行**（见 §2：`E$` 对未知类型原样透传）。

### 4.2 App 侧还要补（必须）

`PiSseClient.toEvents` 不认识 `model_changed`，会落进通用 `else`（什么都不做）。需要：

1. `AgentSseEvent` 增加 `modelProvider` / `modelId` 两个可空字段；
2. `PiSseClient` 增加 `"model_changed" ->` 分支；
3. MainActivity 收到后走与探针相同的应用逻辑（`resolve` + `saveSessionModelIdentity`，受同一套 revision 护栏）；
4. 单测：事件解析、半身份拒识、与本地切换的竞态。

### 4.3 为什么它危险（必须配套护栏脚本）

- 落点是**别人编译好的 node_modules**：`npm i -g @agegr/pi-web@latest`、或
  `start-piweb.bat` 的 `npx -y @agegr/pi-web@latest`（临时目录）都会让它**静默消失**；
- 补丁锚点错位会让 pi-web 直接起不来 → 手机端全部功能瘫痪，回退成本最高。

因此若要实施 B，**同时**提交一个启动前校验脚本（幂等）：

1. 读 SDK 版本 + 文件哈希，已知版本 → 用锚点插入；未知版本 → **拒绝补丁并明确报错**；
2. 已打过（存在 `model_changed`）→ 跳过；
3. 校验：`node -c` 通过 + 起 pi-web 后 `set_model` 能收到事件，否则自动回滚到 `.bak`；
4. 在 `start-piweb.bat` / `start-server*.bat` 前置调用；
5. README 与 CHANGELOG 标注「本地补丁，升级需重打」。

### 4.4 收益 / 代价

- 收益：PC 切模型从 ≤3s 变成瞬时（`thinking_level_changed` 已是瞬时）。
- 代价：升级 pi-web 后必须重打 + 维护锚点；App 也要单独发版。

## 5. C 方案（上游 PR，推荐路径）

给 `github.com/earendil-works/pi` 提 1 行 PR（`setModel` 里 `_emit model_changed`）
+ `AgentSessionEvent` 类型 + 一条断言测试。发布后 App 按 §4.2 加解析即可，
无需任何本地补丁，也不会被升级覆盖。

## 6. 何时考虑切到 B

- 上游迟迟不发，且「PC 切模型 → 手机 3s 延迟」被反复提出；
- 有用户在 PC 端高频切模型（对比 3s 延迟与维护成本）。

否则保持 A：1.9 KB / 3s，比 267 KB 全量快照低两个数量级。
