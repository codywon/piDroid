# 🛡️ piDroid 统合伴侣网关 (Unified Companion Launcher)

为 `pi-web` 提供移动端专用的零侵入安全反向代理网关与一键统合启动中心：
- **🚀 一键统合启动 (Unified Launcher)**：单命令/单脚本自动拉起官方原生 `pi-web` 与伴侣网关，保持官方 100% 独立平滑更新；
- **🔒 动态高熵 Token 安全鉴权**：保护本地 Agent，彻底防止未授权局域网与公网 RCE 远程命令执行风险；
- **📱 终端 ASCII 二维码生成**：App 打开【扫码配对】一键对准终端即可自动握手；
- **🌐 自动探测局域网 IP 与 Cloudflare 临时公网隧道**：随时随地通过蜂窝网络远程控制；
- **🔄 REST 与 SSE / WebSocket 实时双向透明长连接**。

---

## ⚡ 极速启动 (推荐)

### 方式 1: 项目根目录双击启动
- **局域网模式**：双击根目录下的 `start-server.bat`
- **公网隧道模式 (跨网络/5G直连)**：双击根目录下的 `start-server-tunnel.bat`

### 方式 2: 命令行运行
在 `server` 目录下运行：
```bash
# 局域网模式 (自动启动 pi-web 并挂载安全网关输出二维码)
npm start

# 公网模式 (自动启动 pi-web + Cloudflare 临时隧道)
npm run tunnel
```

### 方式 3: 仅运行纯网关 (已有运行中的 pi-web)
如果你已经在其他窗口手动运行了 `pi-web`，启动器会自动感知并直接接入，不会重复启动！你也可以显式运行纯网关：
```bash
npm run gateway
# 或
node launcher.js --no-pi-web
```

---

## 🛠️ CLI 命令行参数

```text
node launcher.js [options]

选项:
  --tunnel          一键自动启用 Cloudflare 临时公网隧道 (免公网 IP，外网手机直连)
  --no-pi-web       仅启动伴侣安全网关，不自动启动/托管本地 pi-web
  --port <port>     自定义伴侣网关监听端口 (默认: 4100)
  --pi-url <url>    自定义目标 pi-web 服务地址 (默认: http://127.0.0.1:30141)
  -h, --help        显示帮助信息
```

---

## 🌟 零侵入设计理念 (Upstream Zero-Intrusion)

本启动器采用业界先进的 **Sidecar / Companion Wrapper** 设计模式：
1. **完全解耦**：不侵入或修改 `@agegr/pi-web` 的任何一行内部代码；
2. **官方无缝升级**：官方发布新版本时，直接通过 `npm update -g @agegr/pi-web` 或 `npx` 升级即可，本网关始终兼容原生；
3. **安全守护**：主进程 Ctrl+C 退出时，联合安全终止并回收子进程，保证端口不被残留占用。

---

## ✨ 提示词增强（伴侣网关自有能力）

手机输入框的「增强提示词」按钮由**网关自己**完成，不经过 pi-web 的会话，
因此不会新增对话条目、不会改标题/模型/思考级别：

```
手机草稿 + 当前分支近期上下文
  → POST /api/mobile/prompt-enhance（Bearer Token 鉴权）
  → 网关用公开 pi SDK 解析 PC 上已配置的模型与凭据
  → 一次独立生成（无工具、无 Agent 系统提示词、不写缓存）
  → 校验受保护片段（代码/引用/URL/路径/变量）后返回
  → 手机确认草稿仍未变化才回填，用户自己点发送
```

### 安装与升级

```bash
cd server
npm install          # 需要 pi SDK（已锁定版本写在 package.json）
```

`GET /api/ping` 现在额外返回能力字段，旧客户端会忽略：

```json
{"capabilities":{"promptEnhancement":{"version":1,"available":true,"maxDraftChars":6000,"maxContextChars":4000}}}
```

### 配置（全部可选，环境变量覆盖）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PROMPT_ENHANCE_DISABLED` | 未设置 | 设为 `1` 关闭该能力，手机端入口随即隐藏 |
| `PROMPT_ENHANCE_MAX_DRAFT` | 6000 | 草稿上限（Unicode 码点），超限直接拒绝，不截断 |
| `PROMPT_ENHANCE_MAX_CONTEXT` | 4000 | 参考上下文总上限 |
| `PROMPT_ENHANCE_MAX_CONTEXT_MESSAGES` | 10 | 上下文最多几条 |
| `PROMPT_ENHANCE_MAX_CONTEXT_MESSAGE` | 800 | 单条上下文上限 |
| `PROMPT_ENHANCE_MAX_OUTPUT` | 10000 | 输出上限 |
| `PROMPT_ENHANCE_OUTPUT_TOKENS` | 4096 | 请求的输出 token 预算（仍受模型上限约束） |
| `PROMPT_ENHANCE_TIMEOUT_MS` | 45000 | 端到端硬超时 |
| `PROMPT_ENHANCE_RATE_LIMIT` | 10 | 每分钟每鉴权主体请求数 |
| `PROMPT_ENHANCE_MAX_CONCURRENT` | 2 | 网关并发生成上限（同一会话恒为 1） |

### 支持范围与已知限制

- 支持：与网关共用同一份 `~/.pi/agent` 配置的模型（内置目录 + `models.json` 自定义 Provider），
  凭据（API Key / OAuth / 自定义认证头）只在 PC 端解析，手机拿不到密钥。
- 不支持：仅存在于 pi-web 进程内存、依赖扩展自定义调用回调或动态注册的模型；
  这类模型会返回 `UNSUPPORTED_MODEL`，**不会静默换成别的模型**。
- 网关代理的是远端 pi-web 时，本机的 `~/.pi/agent` 与远端不是同一份，需要显式配置同样的模型环境。
- 首版不增强：以 `/` 或 `!` 开头的命令/shell 输入、未闭合代码围栏、历史消息编辑态。

### 排查

| 现象 | 原因与处理 |
| --- | --- |
| 手机上不显示增强按钮 | 网关没重启（能力是启动时读的）或 `PROMPT_ENHANCE_DISABLED=1` |
| `ENHANCEMENT_UNAVAILABLE` | `server` 目录没执行 `npm install`，SDK 加载失败 |
| `MODEL_AUTH_FAILED` | PC 端该 Provider 未登录/密钥失效；与网关 Token 失效是两件事 |
| `UNSUPPORTED_MODEL` | 该模型无法独立调用（见「支持范围」） |
| `INVALID_OUTPUT` | 模型改动/丢失了受保护片段或输出被截断；已保留原稿 |

### 测试

```bash
cd server
npm test        # 全部使用假模型，零费用
# 真实调用冒烟（会花钱，仅升级 SDK / 换 Provider 后人工跑一次）
node scripts/smoke-prompt-enhancement.mjs --provider <provider> --model <modelId>
```
