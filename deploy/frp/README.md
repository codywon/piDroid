# deploy/frp —— 服务端（VPS）部署用的可执行文件

默认公网通道是自建 frp。这里是服务端需要的三件套，**文件里没有任何密钥**：

| 文件 | 装到哪 | 作用 |
| --- | --- | --- |
| `frps.example.toml` | `/opt/frp/frps.toml`（先填 token） | frps 主配置：控制端口、token、vhost 端口 |
| `start-frps.sh` | `/opt/frp/start-frps.sh` | 启动包装器：动态决定 vhost 绑哪个地址（关键安全逻辑） |
| `frps.service` | `/etc/systemd/system/frps.service` | systemd 单元，`Restart=always` |

完整步骤、nginx 反代片段、客户端配置和验收命令见 [docs/FRP_TUNNEL.md](../../docs/FRP_TUNNEL.md)。

## 三行安装

```bash
sudo install -Dm 755 start-frps.sh  /opt/frp/start-frps.sh
sudo install -Dm 600 frps.example.toml /opt/frp/frps.toml   # 随后填 auth.token
sudo install -Dm 644 frps.service   /etc/systemd/system/frps.service
```

nginx 跑在容器里时，还要告诉包装器容器名（drop-in，不写进单元文件）：

```bash
sudo mkdir -p /etc/systemd/system/frps.service.d
printf '[Service]\nEnvironment=NGINX_CONTAINER=%s\n' "<容器名>" \
  | sudo tee /etc/systemd/system/frps.service.d/container.conf
```

## 启动与自检

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now frps
systemctl is-active frps
ss -lntp | grep -E ':7000|:8080'   # 8080 必须落在网桥 IP 或 127.0.0.1 上，绝不能是 0.0.0.0
```

`frps.toml` 里的 `bindPort`（控制端口）对外开放；`vhostHTTPPort` 只给 nginx 反代，
**不要**在安全组里放通后者。

## 两个不要

1. **不要在 `frps.toml` 里写 `proxyBindAddr`** —— frp 配置文件优先级高于命令行参数，
   写死会覆盖包装器的探测结果。
2. **不要把真实 token 提交进仓库** —— 本目录只放模板，真值留在服务器上
   （`/opt/frp/frps.toml`，权限 `600`）。
