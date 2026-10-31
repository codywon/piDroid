# 设计：输入框提示词增强

- 状态：**已实现（首版）**
- 计划与验收矩阵：`docs/PLAN-prompt-enhancement.md`
- 代码位置：`server/prompt-enhancement/`、`app/src/main/java/com/pi/app/prompt/`、
  `PiApiClient.enhancePrompt`、`ChatScreen`（输入框增强按钮 + 撤销胶囊）

## 1. 与「生成标题」的复用关系

已确认的标题链路（`POST /api/sessions/{id}/auto-name` → `lib/session-title.ts`）
证明了「用当前会话模型 + 服务端凭据做一次独立 LLM 调用」这件事在本部署上是可行的。
本功能复用它的**设计**（独立请求、独立 session、无工具、不写缓存），但**不复用它的接口**：

| 能力 | 处理 |
| --- | --- |
| 服务端模型与凭据解析 | 复用同一套 `~/.pi/agent` 配置与公开 SDK |
| 独立请求的选项（maxTokens / cacheRetention / sessionId） | 复用同一套思路 |
| `POST /api/sessions/{id}/auto-name` | 不复用：忽略请求正文、固定生成标题并写回会话名 |
| 标题解析（256 token、首行、80 字符） | 不复用：会截断/破坏增强结果 |
| pi-web 进程内的 `streamFunction` | 不复用（网关跨进程拿不到） |

## 2. 调用链

```
ChatScreen 增强按钮
  → PromptEnhancementRequest（requestId / 修订号 / 目标模型 / 草稿 / 裁剪后上下文）
  → PiApiClient.enhancePrompt（OkHttp enqueue + suspendCancellableCoroutine，可取消）
  → 网关 POST /api/mobile/prompt-enhance（Bearer 鉴权，挂在反向代理之前）
  → 校验 → 保护片段 → 公开 SDK 独立生成 → 输出校验 → 恢复保护片段
  → 返回 enhancedText（不落库、不写会话）
  → ChatScreen 比对身份与修订号后才回填，用户自己发送
```

## 3. 关键不变量

1. **增强永不自动发送**，也不写会话历史、不改标题/模型/思考级别/工具权限。
2. **过期即丢弃**：`requestId + 连接身份 + 会话 + 草稿修订 + 分支修订 + 模型身份` 全部匹配才回填。
   用户编辑后又改回原文属于新的修订，旧结果同样作废。
3. **失败不改稿**：任何错误码都只是提示，草稿保持原样。
4. **受保护片段逐字保留**：代码围栏/内联代码/@引用/URL/路径/模板变量先占位、后恢复，
   恢复失败（缺失或残留占位符）即判 `INVALID_OUTPUT`。
5. **凭据不出 PC**：手机只收 `enhancedText` 与用量统计。
6. **能力不存在就不给入口**：旧网关/直连 pi-web 没有 `capabilities.promptEnhancement` 时按钮隐藏。

## 4. 输入边界（首版）

| 输入 | 行为 |
| --- | --- |
| 空草稿 | 按钮禁用 |
| 以 `/` 开头（斜杠命令） | 不增强（客户端与服务端都拦） |
| 以 `!` 开头（shell） | 不增强 |
| 未闭合代码围栏 | 不增强（无法可靠判定边界） |
| 超过草稿上限 | 不增强，提示上限 |
| 历史消息编辑态 | 不增强（首版保守：编辑点之后的分支上下文不完整） |
| 附件 | 不参与增强，也不上传；模型不得臆测附件 |
| 正在切换模型 | 仍可增强：用的是用户刚选的模型（本地切换保护优先），服务端独立解析该模型；若切换失败，本次增强不受影响 |

> 偏差说明：计划里写的是「未确认生效的模型切换时禁用」。实现选择不禁用，因为
> `selectedModel` 已经是「本地切换保护 > 运行时 > 快照」的权威值，且增强请求本身就带
> 显式 provider/modelId 并要求服务端独立解析——把它藏起来只会造成按钮闪烁。

## 5. 服务端契约

`POST /api/mobile/prompt-enhance`

```json
{
  "requestId": "uuid",
  "sessionId": "session-id",
  "draftRevision": 17,
  "branchRevision": 3,
  "provider": "codywon",
  "modelId": "deepseek-flash",
  "draft": "帮我看看 @A.kt 这个报错，先给建议别改代码",
  "context": [{"role": "user", "text": "上一轮的正文"}]
}
```

响应：`{requestId, draftRevision, branchRevision, enhancedText, changed, model:{provider,modelId}, contextUsed, promptVersion, usage}`。

错误：`{requestId, code, message}`，HTTP 映射：

| code | HTTP | 含义 |
| --- | --- | --- |
| `INVALID_INPUT` | 400 | 参数/草稿不合法（含命令前缀、未闭合围栏） |
| `CONTEXT_TOO_LARGE` | 413 | 上下文条数/长度超限 |
| `UNSUPPORTED_MODEL` | 422 | 该模型无法独立调用 |
| `INVALID_OUTPUT` | 422 | 输出被截断、丢片段、多包围栏 |
| `MODEL_AUTH_FAILED` | 502 | Provider 凭据不可用（**不是**网关 Token 失效） |
| `MODEL_REQUEST_FAILED` | 502 | 上游其他失败 |
| `RATE_LIMITED` | 429 | 限流 / 同会话已有请求 / 并发已满 |
| `TIMEOUT` | 504 | 45s 硬超时 |
| `ENHANCEMENT_UNAVAILABLE` | 503 | 网关关闭该能力或 SDK 未安装 |
| `CANCELLED` | 499 | 客户端离开，仅结束连接 |

能力探测：`GET /api/ping` → `capabilities.promptEnhancement`。

## 6. 预算与限流（工程默认值，可环境变量覆盖）

草稿 6000 码点；上下文最多 10 条、单条 800、总计 4000；输出 10000 码点 / 4096 token；
端到端 45s；不自动重试；同会话 1 并发、网关 2 并发、10 次/分钟/鉴权主体。

上下文只取当前分支的用户/助手正文，剔除思考、工具、附件与流式空壳，按「从新到旧」累积预算。

## 7. 思考档位：为什么不用 `getSupportedThinkingLevels`

实测 `codywon/deepseek-flash` 的 `getSupportedThinkingLevels` 会列出 `minimal`，
但上游返回 `400: level "minimal" not supported, valid levels: low, medium, high`。
因此改以模型自己声明的 `thinkingLevelMap`（键 = pi 档位、值 = 供应商字面量、`null` = 不支持）为准：

- 声明了 `off` → 用 `off`（改写不需要推理，最省）
- 没声明 `off` → 取声明里最便宜的一档
- 完全没声明映射 → 不传 `reasoning`，交给 Provider 默认

这条规则由 `server/test/prompt-enhancement.model-client.test.mjs` 锁定。

## 8. 验证现状

| 项目 | 状态 |
| --- | --- |
| 服务端单测（保护/校验/限流/取消/HTTP 状态） | ✅ 24/24 通过（假模型，零费用） |
| 真实独立调用（当前实际模型 `codywon/deepseek-flash`） | ✅ 3.8s，`@ChatScreen.kt` 逐字保留，「先给建议别改代码」保留，usage 641+68 |
| 网关真实挂载（`/api/ping` 能力字段、`/api/mobile/prompt-enhance` 鉴权与 422） | ✅ 手测通过 |
| 网关 HTTP 全链路（真实模型，`codywon/deepseek-flash`） | ✅ 200；`@ChatScreen.kt` 保留、「先给建议 / 别改代码」保留；usage 254+145；修订号原样回显 |
| Android 纯规则单测（`PromptEnhancementRulesTest`） | ⏳ 本机无 Android SDK 未执行；已在文件中断言 16 条，交由 CI / 有 SDK 的环境运行 |
| 模型质量评测（30 条样本 × 3 次） | ⏳ 待执行，需要按 Provider 计费 |
| 真机回归（窄屏/大字号/TalkBack/进程重建） | ⏳ 待真机验证 |

## 9. 回退

- 服务端：`PROMPT_ENHANCE_DISABLED=1` 重启网关 → 能力为不可用，手机端入口隐藏；聊天与标题不受影响。
- 客户端：没有任何新权限或后台任务；移除按钮相关的 UI 代码即可回到旧行为。
