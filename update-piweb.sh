#!/bin/bash
# 升级 pi-web 到最新版本并重启
cd "$(dirname "$0")" || exit 1

echo "1. 正在停止运行中的 pi-web..."
pi-web stop
sleep 2

echo "2. 正在执行 npm 全局升级安装..."
npm install -g @agegr/pi-web@latest

if [ $? -eq 0 ]; then
    echo "[OK] pi-web 升级成功！当前版本：$(pi-web --version)"
    echo "正在重新启动 pi-web..."
    sleep 1
    ./start-piweb.sh
else
    echo "[ERROR] 升级失败，请检查错误提示。"
    exit 1
fi
