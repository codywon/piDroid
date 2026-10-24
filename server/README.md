# Pi Mobile Gateway (安全伴侣网关)

为 `pi-web` 提供移动端专用的安全反向代理网关，提供：
- 动态高熵 Token 安全鉴权（防公网 RCE 扫描）
- 终端 ASCII 二维码生成（App 一键扫码配置）
- 自动探测局域网 IP 与外部隧道支持
- REST 与 SSE 长流透明代理

## 快速使用

### 1. 确保已安装并运行 pi-web
在另一个终端或后台启动 pi-web（默认运行在 `http://127.0.0.1:3000`）：
```bash
npx @agegr/pi-web@latest
```

### 2. 启动安全网关
在 `server` 目录下运行：
```bash
npm install
npm start
```
Windows 下也可以直接双击 `start.bat`。

### 3. 外网穿透模式 (可选)
如果需要从外网（例如蜂窝移动网络、公司外网）随时随地连接家里的 PC，推荐使用 Cloudflare Quick Tunnel（免配置、安全带免费 HTTPS 证书）：
```bash
# 启动 cloudflared
cloudflared tunnel --url http://127.0.0.1:4100
```
然后启动网关时指定生成的域名：
```bash
set TUNNEL_URL=https://your-tunnel-subdomain.trycloudflare.com
node gateway.js
```
终端将自动输出带有外网安全域名的配对二维码，手机 App 扫码即可瞬间连接！
