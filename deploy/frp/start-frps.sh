#!/usr/bin/env bash
# frps 启动包装器 —— 决定 vhost 端口绑在哪个地址上
#
# 为什么需要它（两个真踩过的坑）：
#
# 1) vhost 端口既不能绑 0.0.0.0，也不能绑 127.0.0.1。
#    绑 0.0.0.0 时，任何人 http://<VPS_IP>:<vhostPort> 都能明文访问网关，
#    App 的 Bearer Token 会在代理日志里裸奔；绑 127.0.0.1 时，nginx 跑在容器里，
#    够不到宿主的回环地址。正确做法是绑**nginx 所在 docker 网络的网关地址**：
#    同网络容器可达、公网不可达，且网络重建后 IP 变化也能自愈。
#
# 2) frp 的配置文件优先级**高于命令行参数**。
#    所以绑定地址不能用 --proxy-bind-addr 传参（会被 config 里的值覆盖），
#    而要写进运行期配置副本（默认 /run/frp/frps.toml）。
#
# 失败策略是 fail-closed：探测不到网桥网关时只绑 127.0.0.1，
# 宁可隧道不通，也不把明文入口暴露到公网。
#
# 环境变量（都可在 systemd 的 drop-in 里用 Environment= 指定）：
#   NGINX_CONTAINER  nginx 所在容器名（默认 nginx）。nginx 直装宿主机时设为空串，
#                    此时包装器直接绑 127.0.0.1，nginx 反代到 127.0.0.1:<vhostPort>
#   FRPS_CONFIG      基础配置路径（默认 /opt/frp/frps.toml，只读，不被修改）
#   FRPS_BIN         frps 可执行文件（默认 /opt/frp/frps）
#   RUNTIME_CONFIG   运行期配置副本路径（默认 /run/frp/frps.toml，每次启动重写）
set -euo pipefail

NGINX_CONTAINER="${NGINX_CONTAINER-nginx}"
FRPS_CONFIG="${FRPS_CONFIG:-/opt/frp/frps.toml}"
FRPS_BIN="${FRPS_BIN:-/opt/frp/frps}"
RUNTIME_CONFIG="${RUNTIME_CONFIG:-/run/frp/frps.toml}"

detect_gw() {
  local net
  net="$(docker inspect "$NGINX_CONTAINER" \
    --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' 2>/dev/null \
    | awk '{print $1}' || true)"
  if [ -n "${net:-}" ]; then
    docker network inspect "$net" \
      --format '{{(index .IPAM.Config 0).Gateway}}' 2>/dev/null | head -1 || true
  fi
}

GW=""
if [ -n "${NGINX_CONTAINER}" ]; then
  GW="$(detect_gw)"
fi

if [ -z "${GW:-}" ] || [ "${GW}" = "<no value>" ]; then
  echo "[frps-wrapper] WARN: 未能探测 docker 网桥网关（NGINX_CONTAINER=${NGINX_CONTAINER:-空}），" >&2
  echo "[frps-wrapper]       vhost 退回 127.0.0.1 —— 公网入口将不可用，但不会误暴露。" >&2
  GW="127.0.0.1"
fi

mkdir -p "$(dirname "$RUNTIME_CONFIG")"
cp "$FRPS_CONFIG" "$RUNTIME_CONFIG"
printf '\n# 由 start-frps.sh 在启动时注入，请勿手工修改\nproxyBindAddr = "%s"\n' "$GW" >> "$RUNTIME_CONFIG"

echo "[frps-wrapper] config=${FRPS_CONFIG} -> ${RUNTIME_CONFIG}"
echo "[frps-wrapper] proxy-bind-addr = ${GW}"
exec "$FRPS_BIN" -c "$RUNTIME_CONFIG"
