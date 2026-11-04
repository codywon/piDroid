#!/bin/bash
# 启动官方 pi-web（默认端口 30141），与 server/start-piweb.bat 行为一致
# 脚本在 server/ 下，但 pi-web 历史上是从仓库根目录启动的，故回到上一级。
cd "$(dirname "$0")/.." || exit 1

export PORT=30141

if command -v pi-web >/dev/null 2>&1; then
    echo "Using globally installed pi-web..."
    exec pi-web
else
    echo "Global pi-web not found, falling back to npx @agegr/pi-web@latest ..."
    exec npx -y @agegr/pi-web@latest
fi
