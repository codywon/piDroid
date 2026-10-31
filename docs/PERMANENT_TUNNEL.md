# 固定公网地址（一次扫码，长期可用）

> **通道定位**：默认公网通道已改为自建 frp，见 [FRP_TUNNEL.md](FRP_TUNNEL.md)。
> 本文描述的是 **Cloudflare 通道（可选）** —— 只有 `node launcher.js --tunnel`
> 或 `start-server-tunnel.bat` 才会走这里。两者都能做到「扫码一次长期可用」，
> 区别在网络路径（见两份文档开头的对比表）。

## 为什么以前每次重启都要重新扫码

> 现状：只要能读到 `~/.cloudflared/config.yml` 里指向网关端口的 ingress，
> `start-server-tunnel.bat` 就会直接走**固定域名隧道**（见下面第 0 节），
> 不再需要每次重扫。下面这段解释「为什么过去做不到」，排查时仍有用。

没有配置命名隧道时，`start-server-tunnel.bat` 会退到 **Cloudflare Quick Tunnel**：

```bash
cloudflared tunnel --url http://127.0.0.1:4100
```

Quick Tunnel 是 Cloudflare 提供的「匿名试用隧道」，特点就是：

- 每次进程启动都会分配一个**全新的随机子域名**，形如
  `https://random-three-words-1234.trycloudflare.com`；
- 域名不能被指定、不能被复用，进程退出即失效；
- 免费、免登录、免域名，只适合临时演示。

所以变的是**地址**，不是 Token。Token 保存在 `server/.token`，只要这个文件还在，
网关的访问密钥就一直不变；重启后旧二维码失效，唯一原因是二维码里编码的 URL 变了。

局域网模式下同理：路由器 DHCP 给电脑换了 IP，旧二维码里的 `http://192.168.x.x:4100`
就失效了。解决办法是给电脑做 DHCP 静态保留（路由器里绑定 MAC → IP），或者改用
固定域名隧道。

---

## 现状：默认就走固定域名（无需环境变量）

`server/gateway.js` 会读取 `%USERPROFILE%\.cloudflared\config.yml`：

- 取 `tunnel:` 指向的隧道名（或 UUID）；
- 只在 ingress 中找出 **`service` 端口等于网关端口（默认 4100）** 的那个 `hostname`。

两者都拿到时，`start-server-tunnel.bat` 直接拉起这条命名隧道，公网地址恒为
`https://<该 hostname>`，手机扫码一次即可长期使用。只有 `config.yml` 缺失、
或其中没有指向网关端口的 ingress 时，才回退到 Quick Tunnel。

> 回退到 Quick Tunnel 时，**每次重启地址都会变**，手机端只会表现为
> 「一直自动重连中」——App 无法自愈，因为它无从得知新地址。遇到这个现象：
> 先确认 `cloudflared` 的 `config.yml` 是否指向 4100，再重新扫码。

## 方案一（推荐）：Cloudflare Named Tunnel + 自有域名

Cloudflare 的「命名隧道」把一条隧道绑定到你自己域名下的固定主机名，免费额度足够个人使用。
配置好之后每次启动都是**同一个 URL + 同一个 Token**，手机扫码一次即可长期连接，除非你主动重置 Token。

### 前提

- 一个已托管在 Cloudflare 的域名（NS 指向 Cloudflare 即可，域名本身可以在任意注册商购买）；
- 已安装 `cloudflared`（本机已装：`C:\Program Files (x86)\cloudflared\cloudflared.exe`）。

### 一次性配置（只做一次）

```bat
:: 1. 浏览器授权，选择你的域名
cloudflared tunnel login

:: 2. 创建命名隧道（名字随意，这里叫 pi）
cloudflared tunnel create pi

:: 3. 把固定主机名指向这条隧道（pi.example.com 换成你自己的子域名）
cloudflared tunnel route dns pi pi.example.com
```

第 3 步之后，`pi.example.com` 就永久指向这条隧道，与进程是否运行无关。

### 让隧道指向伴侣网关（不是 pi-web）

新建/编辑 `%USERPROFILE%\.cloudflared\config.yml`：

```yaml
tunnel: pi
credentials-file: C:\Users\<你的用户名>\.cloudflared\<隧道UUID>.json

ingress:
  - hostname: pi.example.com
    service: http://127.0.0.1:4100   # 必须指向网关端口，不是 30141
  - service: http_status:404
```

> `credentials-file` 的 UUID 在 `cloudflared tunnel create` 的输出里，
> 也可以直接看 `%USERPROFILE%\.cloudflared\` 目录下的 `*.json` 文件名。

### 告诉启动器使用固定域名

**通常什么都不用做。** 只要 `~/.cloudflared/config.yml` 里存在
「`tunnel:` + 一条 `service` 指向网关端口（默认 4100）的 `hostname`」，
双击 `start-server-tunnel.bat` 就会自动用它：

- 不再启动临时隧道，改为执行 `cloudflared tunnel run <tunnel>`；
- 直接打印 `https://<hostname>` 的二维码，不再等待解析随机域名；
- 二维码里的 URL 与 Token 每次启动都相同 —— **扫一次，永久可用**。

环境变量仍然可用，作用是**覆盖**自动识别结果（例如配置里有多个 hostname、
或想临时换一条隧道）：

```bat
setx PI_TUNNEL_NAME pi
setx PI_TUNNEL_HOSTNAME pi.example.com
```

排查时注意启动日志里这两类提示：

- `[tunnel] ⚠️ … 的 ingress 指向端口 3081，不是网关端口 4100` —— 配置存在但没匹配上，
  已回退 Quick Tunnel（随机域名，重启即失效）；
- `⚠️ https://… 当前打不开` —— 已经在跑的网关进程不是带 `--tunnel` 启动的。

想彻底退回临时隧道：删掉配置里的那条 ingress，或删掉这两个环境变量。

```bat
reg delete HKCU\Environment /v PI_TUNNEL_NAME /f
reg delete HKCU\Environment /v PI_TUNNEL_HOSTNAME /f
```

---

## 方案二（没有域名）：Tailscale Funnel

不想买域名的话，可以用 Tailscale 的 Funnel，它提供一个固定的
`https://<机器名>.<你的tailnet>.ts.net` 地址：

```bat
tailscale funnel --bg 4100
tailscale funnel status      :: 查看固定域名
```

把该域名直接作为服务器地址手填进 App 即可（不要给启动器加 `--tunnel`，
Funnel 由 Tailscale 自己维护，网关只负责本机 4100 端口的鉴权与代理）。
Tailscale 账号里的机器名不变，地址就不变。

代价是需要在 PC 和手机上都登录同一个 Tailscale 账号，属于「零信任内网」思路，
安全性反而比公网隧道更高（Funnel 只暴露指定端口，其余仍不可达）。

---

## 手机端也可以做到「只配一次」

App 现在保存的是「URL + Token」。只要 URL 固定（方案一/方案二）就不再需要扫码。
如果仍然使用 Quick Tunnel，可以改为：每次启动后把新 URL 手动填进 App 的服务器地址，
Token 保持不变即可，不必重新扫码。后续若需要，可以在 App 侧支持「按固定 Token
自动发现/更新地址」的能力，但那是新功能，当前版本没有。

---

## Token 相关的几个事实

- Token 存放在 `server/.token`，首次启动自动生成，之后一直复用；
- 删除 `server/.token` 会重新生成一个新 Token，所有已配对设备都会失效（需要重新扫码/填写）；
- `.token` 已在 `.gitignore` 中，不会被提交到仓库；
- 二维码里同时编码了 URL 和 Token，**截图/转发二维码等同于交出电脑控制权**。

---

## 安全建议（对应「外网是否容易被入侵」）

1. **Cloudflare Access 目前会挡住手机 App**（重要）：Access 的 OTP/SSO 走的是浏览器
   登录 Cookie 流程，而 App 的 `PiApiClient` / `PiSseClient` 是 OkHttp 直连、只带
   `Authorization: Bearer <token>`。开了 Access 后 App 会收到 302 / 登录页而直接失败。
   要用 Access，需要先给 App 加 **Service Token** 支持（请求头带
   `CF-Access-Client-Id` / `CF-Access-Client-Secret`），这是 App 侧改造，当前版本没有。
2. **零公网暴露的替代方案：Tailscale Serve**（App 无需改造）。
   手机和 PC 登录同一个 tailnet 后：
   ```bat
   winget install Tailscale.Tailscale
   tailscale up
   tailscale serve --bg 4100     :: 仅 tailnet 内可达的 HTTPS 地址
   ```
   得到固定的 `https://<主机名>.<你的tailnet>.ts.net`，只有你自己的设备能访问，
   外网扫描器连端口都看不到，Token 仍需正确。注意不要用 `tailscale funnel`
   （funnel 是公网发布，serve 才是仅内网）。
3. **不要在公共 WiFi 下用局域网模式**：网关的局域网入口是明文 HTTP，同一网络里的
   其他人做 ARP 欺骗/抓包就能拿到 Token。外出请走 HTTPS 隧道。
4. **Token 等同于本机命令行权限**：网关背后是 `pi-web`，它能执行 shell 命令、读写
   任意文件。请按「SSH 私钥」的级别对待 Token，不要放进聊天记录、截图或 Issue。
5. **用完就关**：外网隧道是最直接的暴露面。不需要远程访问时直接关掉
   `start-server-tunnel.bat` 的窗口，比任何加固都有效。
6. **定期轮换**：怀疑泄露时删除 `server/.token` 并重启网关，旧 Token 立刻失效。

> 以上加固的完整升级方案（Cloudflare Access + Service Token 的无感接法、Tailscale Serve、
> WAF 收紧、App 侧改动清单与回滚步骤）见 **[SECURITY_UPGRADES.md](SECURITY_UPGRADES.md)**。
> 当前决策：**先用 Token 方案，Access 待需要时再上**。

---

## 本机当前部署记录（2026-10-28 配置）

| 项目 | 值 |
| --- | --- |
| 固定公网地址 | `https://pi.codywon.top` |
| Cloudflare 区域 | `codywon.top`（zone `8b8150ea…`，Free） |
| 隧道名 / ID | `pi` / `8e192463-4636-4ec1-9664-e9cea401ef9a` |
| DNS | `pi.codywon.top` → CNAME → 上述隧道（显式记录，优先于 `*.codywon.top` 泛解析） |
| 配置文件 | `C:\Users\29092\.cloudflared\config.yml`（ingress → `http://127.0.0.1:4100`） |
| 用户环境变量 | `PI_TUNNEL_NAME=pi`、`PI_TUNNEL_HOSTNAME=pi.codywon.top` |

验证方式（应分别返回 401 / 200）：

```bat
curl -i https://pi.codywon.top/api/ping
curl -i -H "Authorization: Bearer <server/.token 里的值>" https://pi.codywon.top/api/ping
```

改地址只需改三处：`config.yml` 的 `hostname`、`cloudflared tunnel route dns`、
`PI_TUNNEL_HOSTNAME` 环境变量。
