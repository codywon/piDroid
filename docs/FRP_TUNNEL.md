# 自建 frp 隧道（默认公网通道）

> 一条命令就能把 PC 上的伴侣网关挂到自己的服务器上，公网地址固定、Token 不变、
> 手机扫码一次长期可用。本文是可复现的部署 runbook：照着做完即可，不需要改代码。
>
> 本文里的 `<域名>`、`<VPS_IP>`、`<FRP_TOKEN>`、`<网关Token>` 都是占位符。
> **真实值只放在三处**：服务器 `/opt/frp/frps.toml`、本机 frpc 配置、`server/.token`。
> 三者都不要提交进仓库。

---

## 为什么默认是 frp，而不是 Cloudflare

Cloudflare Tunnel 的入口 IP 由 Anycast 决定。国内网络直连经常落到美西边缘节点
（往返 200~400ms），晚高峰还会被运营商 QoS 限速与丢包；隧道自己会重连，但手机端
看到的是**偶发请求失败**与**长时间「获取会话列表失败 / 一直自动重连中」**。

自建服务器 + frp 走普通 TCP（自选端口，不经第三方边缘），实测：

| 指标 | Cloudflare Tunnel | frp（自建） |
| --- | --- | --- |
| `/api/ping` | 0.5~2s 抖动，偶发失败 | 0.19s 稳定 |
| `GET /api/sessions`（310KB） | 大包易卡死超时 | 0.33s（gzip 后 83KB） |
| SSE 事件流 | 时断时续 | 连上即出事件 |

服务端仍需一个公网 VPS 与一个能签证书的域名；只要这两样具备，frp 就是更稳的选择。

---

## 架构

```
手机 App
  │  https://<域名>/pi/api/...        ← 只暴露 443，走 nginx + Let's Encrypt
  ▼
nginx（VPS:443）
  │  location /pi/ → 去掉 /pi 前缀，反代到 frps 的 vhost 端口
  ▼
frps（VPS，vhost 端口只绑 docker 网桥网关或 127.0.0.1）
  │  frp 控制连接：VPS:7000，token 鉴权
  ▼
frpc（你的 PC，由启动器拉起并守护）
  │
  ▼
伴侣网关 127.0.0.1:4100 → pi-web
```

三个刻意的设计点：

1. **vhost 端口绝不绑 `0.0.0.0`**。否则任何人 `http://<VPS_IP>:8080` 就能明文访问网关，
   App 的 Token 会在自己不知道的情况下裸奔。只绑 docker 网桥网关地址时，
   只有同网络的 nginx 容器能访问，公网不可达。
2. **路径前缀 `/pi` 而不是子域名**。这样复用已有的 443 证书，不需要再签一张；
   nginx 末尾带 `/` 的 `proxy_pass` 会剥掉前缀，网关收到的仍是原始 `/api/...`。
3. **公网地址由本机 frpc 配置推导**，代码里不写死域名。换域名只改一份配置。

---

## 一、服务端：装 frps

```bash
# 版本要与客户端的 frpc 一致（不一致会出现难以定位的握手失败）
VER=0.71.1
cd /tmp
curl -fsSL -o frp.tar.gz "https://github.com/fatedier/frp/releases/download/v${VER}/frp_${VER}_linux_amd64.tar.gz"
tar -xzf frp.tar.gz
install -m 755 frp_${VER}_linux_amd64/frps /opt/frp/frps
install -m 755 frp_${VER}_linux_amd64/frpc /opt/frp/frpc   # 可选，方便在同机调试
/opt/frp/frps --version
```

> 国内 VPS 从 GitHub 拉取可能超时。可靠做法是本地下载后 `scp`/SFTP 上传，
> 或用可信镜像；不要为此改成来路不明的二进制。

## 二、服务端：frps.toml

`/opt/frp/frps.toml`：

```toml
bindAddr = "0.0.0.0"
bindPort = 7000            # 控制端口：客户端连这里，安全组需放通

auth.method = "token"
auth.token = "<FRP_TOKEN>" # 高熵随机串，不要复用其它系统的密码

vhostHTTPPort = 8080       # HTTP vhost 端口：只给 nginx 反代用

log.to = "/opt/frp/frps.log"
log.level = "info"
log.maxDays = 7

transport.maxPoolCount = 20
transport.tcpKeepAlive = 7200
transport.heartbeatTimeout = 30
```

**不要在这个文件里写 `proxyBindAddr`**：frp 的配置文件优先级高于命令行参数，
写死会覆盖下面包装器动态探测出来的地址（这也是本 runbook 里唯一容易踩的坑）。

## 三、服务端：启动包装器（决定 vhost 绑在哪）

vhost 端口既不能绑 `0.0.0.0`（公网可明文访问），也不能绑 `127.0.0.1`
（nginx 在容器里，够不到宿主回环）。所以每次启动动态探测 nginx 所在 docker
网络的网关地址：同网络容器可达，公网不可达，网络重建后还能自愈。

`/opt/frp/start-frps.sh`（`chmod +x`）：

```bash
#!/usr/bin/env bash
set -euo pipefail

# 换成你自己的 nginx 容器名
NGINX_CONTAINER="${NGINX_CONTAINER:-nginx}"

detect_gw() {
  local net
  net="$(docker inspect "$NGINX_CONTAINER" \
    --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' 2>/dev/null | awk '{print $1}' || true)"
  if [ -n "${net:-}" ]; then
    docker network inspect "$net" --format '{{(index .IPAM.Config 0).Gateway}}' 2>/dev/null | head -1 || true
  fi
}

GW="$(detect_gw)"
if [ -z "${GW:-}" ] || [ "${GW}" = "<no value>" ]; then
  # 探测失败时 fail-closed：宁可隧道不通，也不把明文入口暴露到公网
  echo "[frps-wrapper] WARN: 未能探测 docker 网桥网关，vhost 退回 127.0.0.1" >&2
  GW="127.0.0.1"
fi

mkdir -p /run/frp
cp /opt/frp/frps.toml /run/frp/frps.toml
printf '\nproxyBindAddr = "%s"\n' "$GW" >> /run/frp/frps.toml

echo "[frps-wrapper] proxy-bind-addr = ${GW}"
exec /opt/frp/frps -c /run/frp/frps.toml
```

若 nginx 不是容器而是宿主直装，把 `GW` 直接设为 `127.0.0.1` 即可，同时**不要**
再监听 `0.0.0.0`。

## 四、服务端：systemd

`/etc/systemd/system/frps.service`：

```ini
[Unit]
Description=frp server (frps) for piDroid companion gateway
Documentation=https://github.com/fatedier/frp
After=network-online.target docker.service
Wants=network-online.target docker.service

[Service]
Type=simple
ExecStart=/opt/frp/start-frps.sh
Restart=always
RestartSec=3
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now frps
systemctl is-active frps
ss -lntp | grep -E ':7000|:8080'   # 8080 应只出现在网桥 IP 或 127.0.0.1 上
curl -s -o /dev/null -w '%{http_code}\n' http://<网桥网关IP>:8080/   # 无匹配 proxy 时 404
```

## 五、服务端：nginx 挂上前缀

站点配置里加（放在已有 `server { }` 内，443 段）：

```nginx
    location = /pi {
        return 301 /pi/;
    }

    location /pi/ {
        # 末尾的 "/" 会把 /pi/ 前缀替换成 /，网关收到的仍是原始 /api/...
        proxy_pass http://<网桥网关IP>:8080/;
        proxy_http_version 1.1;
        # frp 按 Host 匹配 customDomains，必须回填真实域名
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Prefix /pi;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        # SSE（/pi/api/agent/{id}/events）必须关缓冲，否则事件攒在 nginx 里不下发
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;

        client_max_body_size 64m;   # App 上传图片 / 技能文件
    }
```

改完先验证再 reload：

```bash
# nginx 在容器里时：docker exec <容器> nginx -t && docker exec <容器> nginx -s reload
nginx -t && nginx -s reload
```

> 老入口必须照旧：`/`、`/platform/`、`/health` 之类改完后要各回归一次，
> 新增 `location` 不该影响任何既有路由。

---

## 六、客户端：frpc 配置（不入库）

本机 `frpc.toml`（Windows 默认放在 `D:\frpc\frpc-pidroid.toml`）：

```toml
serverAddr = "<域名 或 VPS_IP>"
serverPort = 7000

auth.method = "token"
auth.token = "<FRP_TOKEN>"      # 与服务端 frps.toml 一致

# 国内直连优先 TCP：KCP/QUIC 走 UDP，容易被限速与丢包放大
transport.protocol = "tcp"
transport.tcpMux = true
transport.poolCount = 5
transport.heartbeatInterval = 15
transport.heartbeatTimeout = 90

# 断网/开机时不要直接退出，交给启动器重启
loginFailExit = false

log.to = "console"
log.level = "info"

[[proxies]]
name = "pidroid_gateway"
type = "http"
localIP = "127.0.0.1"
localPort = 4100                        # 必须等于网关实际监听端口，否则通了也 502
customDomains = ["<域名>"]              # 与 nginx 回填的 Host 一致
locations = ["/"]                       # /pi 前缀已被 nginx 剥掉，这里匹配根
```

启动：

```bash
node server/launcher.js          # 默认就是 frp，公网地址由上面的 customDomains 推出
node server/launcher.js --local  # 只局域网
node server/launcher.js --tunnel # 改用 Cloudflare
```

覆盖项（一般不用改）：

| 变量 | 作用 |
| --- | --- |
| `PI_FRPC_CONFIG` | 指定其它 frpc 配置路径 |
| `PI_FRPC_BIN` | 指定 frpc 可执行文件（默认 `D:\frpc\frpc.exe`，其次 PATH） |
| `PI_PUBLIC_URL` | 直接指定公网地址，覆盖推导结果 |
| `PI_PUBLIC_PATH` | 路径前缀，默认 `/pi`；改成 `/` 表示直接占域名根 |

App 里填的**服务器地址**就是公网地址（形如 `https://<域名>/pi`，注意带前缀），
Token 与之前完全一致 —— 换通道不需要重新配对。

---

## 七、验收（四步，逐条都要过）

```bash
T='<网关Token>'
BASE='https://<域名>/pi'

curl -s -o /dev/null -w '401? %{http_code}\n'      "$BASE/api/ping"                        # 期望 401
curl -s -o /dev/null -w '200? %{http_code} %{time_total}s\n' -H "Authorization: Bearer $T" "$BASE/api/ping"
curl -s --compressed -o /dev/null -w 'sessions %{size_download}B %{time_total}s\n' \
     -H "Authorization: Bearer $T" "$BASE/api/sessions"
# SSE：应在几秒内看到 data: {"type":"connected",...}
curl -N -m 6 -H 'Accept: text/event-stream' -H "Authorization: Bearer $T" \
     "$BASE/api/agent/<sessionId>/events"
```

外加两条安全验收：

```bash
# 1) 公网 vhost 端口必须不可达
curl -s -m 3 -o /dev/null -w '%{http_code}\n' http://<VPS_IP>:8080/ || echo BLOCKED
# 2) 不带 token 的 SSE 不能连上（应为 401，不是 200）
curl -s -m 5 -o /dev/null -w '%{http_code}\n' -H 'Accept: text/event-stream' \
     "$BASE/api/agent/<sessionId>/events"
```

---

## 八、排查表

| 现象 | 原因与处理 |
| --- | --- |
| 启动日志没有 `[frp]` 行 | 找不到 frpc 或配置：设 `PI_FRPC_BIN` / `PI_FRPC_CONFIG` |
| `404` 而不是 `401` | frpc 没连上，或 `customDomains` 与 nginx 的 `Host` 不一致（frp 按 Host 匹配） |
| `502` | toml 的 `localPort` 与网关实际端口不一致（启动器会打告警） |
| 公网 `:8080` 能打开 | `proxyBindAddr` 被写死在 `frps.toml` 里，或包装器探测失败退回了 `0.0.0.0` |
| 改配置不生效 | frp 配置文件优先级高于命令行参数；`/run/frp/frps.toml` 是运行期副本，改完要 `systemctl restart frps` |
| 手机一直重连 | `systemctl status frps`、`journalctl -u frps -n 50`、`ss -lnt \| grep 7000` |
| 握手失败/版本告警 | 服务端 frps 与客户端 frpc 版本不一致 |
| 手机侧 `timeout` 但本地正常 | 安全组没放通 7000，或 VPS 侧 iptables 拦了 |
| 换域名后旧地址 404 | 需同步三处：frpc `customDomains`、nginx `Host`/前缀、App 地址 |

---

## 九、轮换与回退

- **轮换 frp token**：改服务端 `frps.toml` → `systemctl restart frps` → 改各客户端配置 → 重启启动器。
  网关 Token（`server/.token`）不受影响，App 不用重新配对。
- **回退到 Cloudflare**：`node server/launcher.js --tunnel`；想两个入口并存，
  可另开一条 `cloudflared tunnel run <名字>` 指向同一个 `127.0.0.1:4100`
  （隧道与网关是解耦的，互不影响）。
- **只想局域网**：`--local`，或删掉 `[[proxies]]` 段落。启动器会退回展示局域网地址。

Cloudflare 侧的配置细节见 [PERMANENT_TUNNEL.md](PERMANENT_TUNNEL.md)。
