#!/bin/bash
cd "$(dirname "$0")/server" || exit 1

if [ ! -d "node_modules" ]; then
    echo "Installing server dependencies..."
    npm install
fi

node launcher.js "$@"
