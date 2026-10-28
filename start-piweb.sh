#!/bin/bash
# 启动官方原生 pi-web（默认端口 30141），与 start-piweb.bat 行为一致
cd "$(dirname "$0")" || exit 1

export PORT=30141

if command -v pi-web >/dev/null 2>&1; then
    echo "Using globally installed pi-web..."
    exec pi-web
else
    echo "Global pi-web not found, falling back to npx @agegr/pi-web@latest ..."
    exec npx -y @agegr/pi-web@latest
fi
