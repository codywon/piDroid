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
