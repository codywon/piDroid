# 安全加固与 Access 升级方案（决策记录 / 待实施）

> 状态：**暂不实施，先用 Token 方案**。本文把可选升级路径、具体改法、验收与回滚
> 全部写清楚，等以后需要时按清单执行，不需要重新调研。
> 最后更新：2026-10-28（配置固定域名 `pi.codywon.top` 当天）

---

## 1. 当前方案（Token-only）

```
手机 App ──TLS──> Cloudflare 边缘 ──> cloudflared(出站隧道) ──> 网关 4100 ──> pi-web 30141
                                    （无入站端口、无公网 IP）      └ Bearer Token 鉴权
```

已落地的防护（代码在 `server/gateway.js`）：

| 项 | 实现 |
| --- | --- |
| Token 强度 | `pi_` + 16 字节随机（128 bit），`crypto.randomBytes` |
| 比较方式 | `crypto.timingSafeEqual`，恒定时间，防逐字节侧信道 |
| 存储 | `server/.token`，权限 0600，已在 `.gitignore`；空/过短自动重建 |
| 传输位置 | 默认只认 `Authorization: Bearer`；仅 SSE `/events` 允许 `?token=`（EventSource 无法设头） |
| WebSocket | `server.on('upgrade')` 单独校验 Token，堵住 Express 中间件管不到的 upgrade 通道 |
| 暴露面 | 只暴露网关 4100；pi-web 绑 `127.0.0.1:30141`，公网不可达 |
| 日志脱敏 | 隧道日志过滤 SSE 断开噪声，并把 `?token=` 打码（`server/gateway.js` 的 `pipeTunnelLogs`）；App 侧 `PiSseClient` 不再把 token 放进 URL（需重打 APK 生效） |
| 401 机器可读 | 网关 401 固定带 `WWW-Authenticate: Bearer realm="pi-gateway", error="invalid_token"` 与响应体 `{"code":"TOKEN_INVALID"}`；App 只认这个标记才判「密钥失效」——pi-web 自己的 `401 Invalid API key`（模型 Key 填错）不参与判定 |
| 401 日志节流 | 只节流「压根没带 token」的（公网扫描器，3s 一条）；**带了 token 却不匹配的永远记**，避免扫描器刷屏时把轮换 Token 后 App 的失败记录挤掉 |

**核心事实**：Token 等价于本机命令行权限（pi-web 能执行 shell、读写任意文件），
所以它必须按 SSH 私钥对待。当前没有第二因子。

### ⚠️ 轮换 Token 的操作顺序（踩过的坑）

旧版 App（≤ 1.7.26）的 `AuthInterceptor` 会无条件用本地已存的 Token 覆盖请求头，
而握手成功后才会写入新 Token。于是轮换后扫码会变成死循环：扫码带新 Token → 被旧 Token
覆盖 → 401 → 不保存新 Token → 下次还是旧 Token。

**正确顺序**：

1. 在 App 里「设置 → 断开当前网关连接」（内部 `prefs.clear()`）；
2. 再扫新二维码（或清应用数据后重扫）；
3. 删除 `server/.token` 并重启网关 —— 或者反过来先删体重启再断连接扫码。

已修：`AuthInterceptor` 现在尊重请求自带的 `Authorization`（等下次构建生效）。
诊断：网关会把 401 写入 `server/logs/auth-fail.log`（只存 Token 的 SHA-256 前 8 位指纹、
长度、路径、UA，不存原值），可用来判断客户端发的是旧 Token 还是压根没带。

> 1.7.61 补的是这个坑的**尾巴**：轮换后即使能重新配对，在那之前 App 的各条轮询
> （`/api/agent/running` 每 2.5s）仍在用旧 Token 静默 401，界面上只表现为「怎么都不更新」。
> 现在这种 401 会被 `AuthRejectionSignal` 接住：停掉轮询、弹一次「密钥已失效，请重新扫码配对」，
> 重扫后第一条成功请求自动复位并重挂 SSE。`server/logs/auth-fail.log` 里 `token=present(len=35)`
> 的行就是这一类（`fp=` 与 `expected=` 不同 = 手里是旧值）。

### 威胁模型（决定要不要加 Access 的关键）

- 使用者：**只有自己的手机**，国内网络；
- 现实风险：二维码/截图/终端回滚/日志导致 Token 外泄 → 单点失守；
- 非风险：公网扫描、暴力破解（128 bit 不可猜，且扫描器只拿到 401）。

---

## 2. 方案 A：Cloudflare Access + Service Token（首选升级路径）

### 2.1 它是什么，以及「每次启动要不要人工验证」

- Access 是 Cloudflare 边缘的门禁：请求到你的隧道前先验身份。
- **浏览器**走交互式登录（邮箱 OTP / Google / GitHub），登录页域名 `cloudflareaccess.com`。
- **App 走 Service Token（机器凭证）**：请求里带两个头即可放行，**没有任何人工交互、
  没有验证码、没有登录页、没有过期续期**，App 启动与今天一模一样。

```
带 Service Token 后：
手机 App ──CF-Access-Client-Id/Secret──> Cloudflare 边缘(第一道锁) ──> 网关(Bearer, 第二道锁) ──> pi-web
浏览器访问公网域名 ──> OTP 登录页(仅浏览器) ──> 同上
```

### 2.2 Cloudflare 后台配置（约 5 分钟）

1. Zero Trust → **Access → Service Auth → Create Service Token**
   - 得到 `Client ID`（形如 `xxxx.access`）与 `Client Secret`（只显示一次，立即保存）。
2. Zero Trust → **Access → Applications → Add an application → Self-hosted**
   - Application domain：`pi.codywon.top`；
   - Session duration：按需（浏览器 Cookie 有效期，与 App 无关）。
3. 该应用下建两条策略：
   - **Action: Service Auth** → 选上一步的 Service Token（给 App 用，静默通过）；
   - **Action: Allow** → Include `Emails = 你的邮箱`（给你自己用浏览器时走 OTP）。
   - 不要加 Bypass，否则等于没开。
4. 验证：
   ```bash
   # 不带头 → 应为 403（在边缘就被拦，电脑收不到包）
   curl -i https://pi.codywon.top/api/ping
   # 带机器凭证 → 应能进入网关，无 Token 时返回 401（说明已过 Access）
   curl -i -H "CF-Access-Client-Id: <id>" -H "CF-Access-Client-Secret: <secret>" \
        https://pi.codywon.top/api/ping
   ```

### 2.3 App 侧改动清单（约 30–50 行，需重打 APK）

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/pi/app/data/storage/AppPreferences.kt` | 新增 `cfAccessClientId` / `cfAccessClientSecret`（默认空字符串，空 = 不启用） |
| `app/src/main/java/com/pi/app/network/AuthInterceptor.kt` | 现有拦截器只加 `Authorization`；追加两个头：非空时 `CF-Access-Client-Id` / `CF-Access-Client-Secret` |
| `app/src/main/java/com/pi/app/network/PiSseClient.kt` | 其 `OkHttpClient.Builder()` 目前未挂 `AuthInterceptor`（Bearer 是手动加的），需 `.addInterceptor(AuthInterceptor(prefs))`，否则 SSE 长连接会被 Access 402/403 |
| 设置/配对界面 | 加两个可选输入框（建议折叠在「高级」里），并回显"已配置/未配置"，不回显明文 secret |
| `PiApiClient` 内的图片/文件请求 | 复用同一 client 即自动生效；若走 Coil，需给 Coil 的 OkHttp 或 ImageLoader 也挂同一拦截器（**实施时要逐一确认**） |

安全要求：
- **绝对不要**把 Client Secret 放进二维码 / `pidroid://connect` 深链 —— 二维码会被截图、会被其它 App 读取；secret 只能手输。
- App 端 secret 存 SharedPreferences 即可，但应禁止日志打印；`AuthInterceptor` 不要打印 header。

### 2.4 运维

- **轮换**：后台重新生成 Service Token → 手机上改一次即可，无需重新扫码（URL/Token 不变）。
- **丢失手机**：撤销 Service Token（边缘立刻 403），再删 `server/.token` 换新 Token。
- **回滚**：Access 应用里把两条策略删掉/禁用即恢复 Token-only，App 侧保留空配置项不受影响。

### 2.5 已知限制

- Secret 存在手机上，设备被完全控制时无效；
- Cloudflare 是 TLS 终点，仍可见明文（所有 CDN/反代共性）；
- 国内访问 `cloudflareaccess.com` 登录页可能不稳 —— **但 Service Token 请求不经过该页面**，
  所以对 App 无影响，只影响你偶尔用浏览器访问。

---

## 3. 方案 B：Tailscale Serve（零公网暴露，App 不改造）

适合"想彻底不暴露公网"或"觉得 Cloudflare 国内链路慢"：

```bat
winget install Tailscale.Tailscale
tailscale up
tailscale serve --bg 4100        :: 仅 tailnet 内可达的 HTTPS
```

- 得到固定 `https://<主机名>.<tailnet>.ts.net`，只有登录同一 tailnet 的设备能访问；
- App 只需把服务器地址改成该域名，代码零改动；
- 国内多为直连打洞，延迟通常优于绕境外的 Cloudflare 免费版；
- **不要用 `funnel`**（那是发布到公网），`serve` 才是内网。

代价：PC 与手机都要装 Tailscale 并登录同一账号。

---

## 4. 方案 C：Cloudflare WAF 收紧（不改 App，低成本）

在 Cloudflare → Security → WAF → Custom rules 里加规则（免费版有少量规则额度）：

- 按国家/地区允许（注意：境外代理可伪造来源国，只能挡掉"低质量"扫描）；
- 对 `/api/*` 加速率限制（防探测/防凭证试错刷量）；
- 拦截明显恶意 UA / 空 UA；
- 关闭不需要的方法（如只允许 GET/POST/PUT/DELETE）。

注意：手机移动网络出口 IP 会变，**不要**用 IP 白名单，否则出门就断。

---

## 5. 方案 D：远期选项（现阶段不建议）

- **mTLS / 客户端证书**：安全性最高，但 Android 侧要做证书管理，维护成本高；
- **WARP / Cloudflare One 客户端**：把设备接入零信任网络，私网访问，不暴露公网；
- **短期 JWT 换取**：网关签发短时票据，App 定期刷新 —— 本质是把长期 Token 换成短期凭证，
  能降低泄露后的时间窗口，但要求 App 实现刷新逻辑；
- **设备绑定**：首次配对时把设备指纹写入服务端白名单，换机需重新配对。

---

## 6. 决策记录

| 日期 | 决策 | 理由 |
| --- | --- | --- |
| 2026-10-28 | 采用 Cloudflare Named Tunnel + 固定域名 `pi.codywon.top`，Token-only | 单人单机使用；扫码一次永久可用；避免引入额外密钥与 App 版本分裂 |
| 2026-10-28 | **暂不启用 Access** | 单人手机场景收益有限；需要改 App 并重打包；当前"不用就关隧道"已能显著降低暴露面 |
| 2026-10-28 | Access 与 App 的兼容性问题记录在案 | App 是 OkHttp + Bearer，Access 的 OTP 会导致 302/HTML，必须用 Service Token 才能无感 |

**重新评估的触发条件**（满足任一条就该回来实施方案 A）：

1. 需要把地址分享给第二个人或第二台设备；
2. 发现 Token 有泄露迹象（出现在聊天记录、截图、日志、公开仓库等）；
3. 需要长期 7×24 开隧道（不再是"用完就关"）；
4. 想在公网域名上直接用浏览器访问，且希望有登录门禁与访问日志。

---

## 7. 通用实施检查清单（方案 A 落地时照做）

- [ ] 后台建 Service Token，secret 只存密码管理器，不进仓库
- [ ] 建 Access 应用 + Service Auth 策略 + Allow(邮箱) 策略
- [ ] `curl` 验证：无头 403、带头 401（网关层），带两台凭证 200
- [ ] App：`AppPreferences` 两个字段 + `AuthInterceptor` 两个头
- [ ] App：`PiSseClient` 挂上拦截器（否则流式事件断）
- [ ] App：确认图片/文件预览链路（Coil 或自建 OkHttp）也带上头
- [ ] 设置页：留空时行为与今天完全一致（回归测试旧配置）
- [ ] 更新本文件第 6 节决策表与 `docs/PERMANENT_TUNNEL.md` 的安全章节
- [ ] 记录回滚步骤并在 5 分钟内能验证回滚成功
