# 固定公网地址（一次扫码，长期可用）

## 为什么现在每次重启都要重新扫码

`start-server-tunnel.bat` 默认走的是 **Cloudflare Quick Tunnel**：

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

设置两个用户级环境变量（设置一次，永久生效，重开终端生效）：

```bat
setx PI_TUNNEL_NAME pi
setx PI_TUNNEL_HOSTNAME pi.example.com
```

之后照常双击 `start-server-tunnel.bat`。启动器检测到这两个变量后：

- 不再启动临时隧道，改为执行 `cloudflared tunnel run pi`；
- 直接打印 `https://pi.example.com` 的二维码，不再等待解析随机域名；
- 二维码里的 URL 与 Token 每次启动都相同 —— **扫一次，永久可用**。

想退回临时隧道，删掉这两个环境变量即可：

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

1. **优先用命名隧道 + Cloudflare Access**：在 Cloudflare Zero Trust 里给
   `pi.example.com` 建一个 Access 应用，加一条邮箱 OTP / Google 登录策略。
   这样外网访问者连「输入 Token」的机会都没有，要先进过 SSO，Token 成为第二道锁。
   免费额度 50 用户，个人完全够用。
2. **不要在公共 WiFi 下用局域网模式**：网关的局域网入口是明文 HTTP，同一网络里的
   其他人做 ARP 欺骗/抓包就能拿到 Token。外出请走 HTTPS 隧道。
3. **Token 等同于本机命令行权限**：网关背后是 `pi-web`，它能执行 shell 命令、读写
   任意文件。请按「SSH 私钥」的级别对待 Token，不要放进聊天记录、截图或 Issue。
4. **只在自己信任的网络里开隧道**：Quick Tunnel 的地址虽然是随机生成，但仍是公网可达；
   不想被任何人扫到时把隧道关掉，或者只开命名隧道 + Access。
5. **定期轮换**：怀疑泄露时删除 `server/.token` 并重启网关，旧 Token 立刻失效。
