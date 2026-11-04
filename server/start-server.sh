#!/bin/bash
# Windows: server/start-server.bat (same behaviour)
# This script lives in server/, so the launcher runs from here.
# Pass args through, e.g.  ./start-server.sh --tunnel   (Cloudflare tunnel)
cd "$(dirname "$0")" || exit 1

if [ ! -d "node_modules" ]; then
    echo "Installing server dependencies..."
    npm install
fi

node launcher.js "$@"
