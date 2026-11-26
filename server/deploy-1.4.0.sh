#!/usr/bin/env bash
set -euo pipefail

# 在树莓派上执行；执行前请先用 scp 把 1.4.0 的 app.py 放到
# /opt/daytally-sync/app.py.1.4.0 或临时目录。
# 用法：sudo bash deploy-1.4.0.sh /opt/daytally-sync/app.py.1.4.0

SRC="${1:-/opt/daytally-sync/app.py.1.4.0}"
DIR="/opt/daytally-sync"
STAMP="$(date +%Y%m%d-%H%M%S)"

if [ ! -f "$SRC" ]; then
  echo "找不到新源码：$SRC" >&2
  exit 1
fi

cd "$DIR"
cp app.py "app.py.bak-1.3.0-$STAMP"
cp "$SRC" app.py
docker compose up -d --build
sleep 3
curl -fsS http://127.0.0.1:8787/health
echo
