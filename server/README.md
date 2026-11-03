# 🛡️ πDroid 统合伴侣网关 (Unified Companion Launcher)

为 `pi-web` 提供移动端专用的零侵入安全反向代理网关与一键统合启动中心：
- **🚀 一键统合启动 (Unified Launcher)**：单命令/单脚本自动拉起官方原生 `pi-web` 与伴侣网关，保持官方 100% 独立平滑更新；
- **🔒 动态高熵 Token 安全鉴权**：保护本地 Agent，彻底防止未授权局域网与公网 RCE 远程命令执行风险；
- **📱 终端 ASCII 二维码生成**：App 打开【扫码配对】一键对准终端即可自动握手；
- **🌐 公网通道双选**：默认走自建 **frp 隧道**（TCP 直连，地址固定），也可一键切回 Cloudflare 隧道；纯局域网模式照旧可用；
- **🔄 REST 与 SSE / WebSocket 实时双向透明长连接**。

---

## ⚡ 极速启动 (推荐)

### 方式 1: 项目根目录双击启动
- **默认模式（frp 公网隧道 + 局域网）**：双击根目录下的 `start-server.bat`
- **改用 Cloudflare 隧道**：双击根目录下的 `start-server-tunnel.bat`

### 方式 2: 命令行运行
在 `server` 目录下运行：
```bash
# 默认模式：自动启动 pi-web + 网关 + frp 隧道（公网地址固定）
npm start

# 改用 Cloudflare 隧道
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

## 🪟 开机自启（Windows 计划任务，无窗口）

默认公网通道是 frp，但它需要网关常驻，所以「自启动」= 让**整条链路**登录后自己起来；
只单独跑一个 frpc 没有意义（网关不在，隧道通了也是 502）。

触发条件是「用户登录后 20 秒」，不是开机前：pi-web 要读当前用户目录下的 pi 凭据（`%USERPROFILE%\.pi`），
改用 SYSTEM 账户在登录前启动会读不到，反而起不来。如果希望开着机就有人已登录，开 Windows 自动登录即可。

一次性注册（以当前用户身份运行、只在登录时启动）：

```powershell
$name = 'piDroid Gateway (frp)'
$repo = 'D:\myscript\pi-app'
$action   = New-ScheduledTaskAction -Execute 'wscript.exe' `
              -Argument ('"' + (Join-Path $repo 'server\start-hidden.vbs') + '"') `
              -WorkingDirectory (Join-Path $repo 'server')
$trigger  = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$trigger.Delay = 'PT20S'                       # 等网络就绪
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
              -StartWhenAvailable -MultipleInstances IgnoreNew
$settings.ExecutionTimeLimit = 'PT0S'          # 默认 3 天会砍掉，必须关
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Force
```

| 文件 | 作用 |
| --- | --- |
| `start-hidden.vbs` | `Run(..., 0, True)` 隐藏窗口启动，并把退出码交回计划任务（任务因此显示 Running 而不是瞬间"成功"） |
| `start-hidden.cmd` | 真正的守护：切目录 → 轮转日志 → 补依赖 → **单实例守卫** → `launcher.js` 退出后 5 秒重试 |
| `stop-gateway.ps1` / `.cmd` | 按命令行匹配杀掉整棵树；只碰 `node/cmd/wscript/frpc`，不误伤其它隧道 |

`launcher.js` 同时托管本地 pi-web（30141），所以**不需要第二个计划任务**：

- 30141 空闲 → 由 `launcher.js` 自己拉起 `pi-web --no-open`（后台常驻，不再自动弹浏览器）；
- pi-web 进程崩溃 → 同一个 launcher 5 秒后自动重启它（日志里是 `[pi-web] 已退出 ... 5s 后自动重启`）；
- 30141 已被占用（例如手动开了 `start-piweb.bat`）→ 直接「接入」现有实例，
  **但不接管它的生命周期**：那个窗口一关，服务就没了。

所以日常**不要**再用 `start-piweb.bat` / `start.bat` 起服务——那是调试入口，它会占住 30141，
把计划任务降级成旁观者，还得一直挂着黑窗口。要常驻就用计划任务，要停就 `stop-gateway.cmd`。

### 日常操作

```bat
schtasks /Run  /TN "piDroid Gateway (frp)"    :: 启动
server\stop-gateway.cmd                       :: 停止（唯一可靠的停止方式）
schtasks /Query /TN "piDroid Gateway (frp)"   :: 看状态
```

日志：`server\logs\gateway.log`（超过 5 MB 时轮转为 `.log.1`）。

### 无窗口模式下怎么拿配对二维码

网关自启后没有窗口，启动横幅与二维码只写进了日志（而且被 ANSI 转义搅花）。
需要重新配对时（Token 轮换、App 重装）执行：

```bat
server\show-pairing.cmd
```

它会读出 `server\.token` 与 frpc 配置里的 `customDomains`，现场打印**公网地址**与
**局域网地址**两个二维码 + 明文 Token，**不重启、不打断**正在运行的服务。
（脚本调用时加 `--no-pause` 可避免停在"按任意键继续"。）

### 三个坑（都是实测踩出来的）

1. **`schtasks /End` 停不干净**：Task Scheduler 只结束动作进程（wscript），
   `cmd → node → frpc` 会变成孤儿继续占着 4100，而任务状态显示已停止。
   所以提供 `stop-gateway`，同时启动脚本带单实例守卫，重复触发不会起第二份。
2. **`timeout /t` 不能当退避**：它需要交互式控制台；计划任务里 stdin 是重定向的，
   会立即返回，循环退化成热转。改用 `ping -n N 127.0.0.1 >nul`。
3. **`.cmd` / `.vbs` 只能写 ASCII**：cmd 与 wscript 按 OEM 代码页读文件，
   UTF-8 中文注释会乱码，甚至把注释当命令执行——第一次试就是被这个坑到的。
   `.gitattributes` 已固定 `*.cmd` / `*.vbs` 为 CRLF。

> 取消开机自启：`Unregister-ScheduledTask -TaskName 'piDroid Gateway (frp)' -Confirm:$false`

---

## 🛠️ CLI 命令行参数

```text
node launcher.js [options]

选项:
  (默认)            frp 隧道：走自建服务器，公网地址固定（推荐）
  --frp             显式指定 frp 隧道（等同默认）
  --tunnel          改用 Cloudflare 隧道：命名隧道优先，否则临时 trycloudflare 域名
  --local           只开局域网，不建任何公网隧道
  --public-url <u>  手动指定公网地址（默认由 frpc 配置的 customDomains 推导）
  --no-pi-web       仅启动伴侣安全网关，不自动启动/托管本地 pi-web
  --port <port>     自定义伴侣网关监听端口 (默认: 4100)
  --pi-url <url>    自定义目标 pi-web 服务地址 (默认: http://127.0.0.1:30141)
  -h, --help        显示帮助信息
```

---

## 🌐 公网通道：默认 frp，Cloudflare 可选

**为什么换默认**：Cloudflare 的入口 IP 由 Anycast 决定，国内直连经常绕到美国西海岸
（200~400ms）并在高峰被 QoS 丢包，手机端表现就是「获取会话列表失败」「一直自动重连中」。
自建服务器 + frp 走 TCP 直连，实测 `GET /api/sessions`（310KB → gzip 83KB）约 0.27s。

### 链路

```
手机 App → https://pi.example.com/pi/api/...   (nginx 443 + Let's Encrypt)
         → frps vhost（绑 docker 网桥网关，仅容器可达，公网 8080 关闭）
         → frpc（本机，由启动器守护）→ 127.0.0.1:4100（伴侣网关）
```

App 里的**服务器地址**就是 `https://<你的域名>/pi`（带路径前缀，Token 与原来完全一致）。
真实域名只写在未入库的 frpc 配置里（`customDomains`），仓库中不出现具体域名与服务器地址。

### 本机配置

| 文件 | 作用 |
| --- | --- |
| `D:\frpc\frpc-pidroid.toml` | 客户端配置真源：serverAddr/serverPort/token/customDomains/localPort |
| `D:\frpc\frpc.exe` | frpc 可执行文件（与服务端 frps 版本保持一致） |

覆盖项（一般不用改）：

| 变量 | 说明 |
| --- | --- |
| `PI_FRPC_CONFIG` | 指定其它 frpc 配置路径 |
| `PI_FRPC_BIN` | 指定 frpc 可执行文件 |
| `PI_PUBLIC_URL` | 直接指定公网地址（默认由 customDomains 推导） |
| `PI_PUBLIC_PATH` | 路径前缀，默认 `/pi`（改成 `/` 表示直接占域名根） |

### 服务器侧（自建 VPS + nginx）

| 路径 | 说明 |
| --- | --- |
| `/opt/frp/frps` | frps 二进制（建议与服务端 frpc 同版本） |
| `/opt/frp/frps.toml` | `bindPort`（控制端口）+ `vhostHTTPPort`（vhost 端口）+ `auth.token` |
| `/opt/frp/start-frps.sh` | 启动包装器：动态探测 docker 网桥网关并注入 `proxyBindAddr` |
| `systemctl status/restart frps` | systemd 服务（建议 `enable` 开机自启 + `Restart=always`） |
| 站点 nginx 配置 | 新增 `location /pi/` 反代到 frps vhost，剥掉前缀、关缓冲（SSE） |

> 服务端三件套的可执行版本在 [`deploy/frp/`](../deploy/frp/)（模板 + 启动包装器 + systemd 单元，
> 不含任何密钥）；完整步骤与验收命令见 [docs/FRP_TUNNEL.md](../docs/FRP_TUNNEL.md)。

> vhost 端口刻意**不绑 0.0.0.0**：否则公网可明文直连 `:8080`，App Token 会裸奔。
> 包装器探测失败时 fail-closed 退回 127.0.0.1（宁可隧道不通也不暴露）。

### 排查

| 现象 | 原因与处理 |
| --- | --- |
| 启动日志没有 `[frp]` 行 | 找不到 frpc 或配置：设 `PI_FRPC_BIN` / `PI_FRPC_CONFIG` |
| `404` 而不是 401 | frpc 没连上，或 `customDomains` 与域名不一致（frp 按 Host 匹配） |
| `502` | toml 的 `localPort` 与网关实际端口不一致（启动器会告警） |
| 手机一直重连 | 服务器 `systemctl status frps`；`journalctl -u frps -n 50` |
| 隧道地址变了 | 改了 `customDomains` → 需同步 nginx `location /pi/` 与 App 地址 |

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
