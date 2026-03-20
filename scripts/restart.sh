#!/usr/bin/env bash
set -euo pipefail

PID_FILE="/tmp/rewayaat.pid"
PORT="${PORT:-8002}"
APP_BASE_URL="${APP_BASE_URL:-http://localhost:${PORT}}"
LOG_FILE="/tmp/rewayaat-${PORT}.log"

if [ -f "$PID_FILE" ]; then
  PID=$(cat "$PID_FILE" || true)
  if [ -n "${PID:-}" ] && ps -p "$PID" -o comm= >/dev/null 2>&1; then
    kill "$PID" || true
    sleep 2
  fi
  rm -f "$PID_FILE"
fi

# Fallback: stop any process holding the HTTP port
PORT_PID=""
if command -v lsof >/dev/null 2>&1; then
  PORT_PID=$(lsof -ti TCP:"$PORT" -sTCP:LISTEN || true)
fi
if [ -z "${PORT_PID:-}" ] && command -v ss >/dev/null 2>&1; then
  PORT_PID=$(ss -ltnp 2>/dev/null | sed -n "s/.*:${PORT} .*pid=\\([0-9]*\\).*/\\1/p" | head -n 1 || true)
fi
if [ -n "${PORT_PID:-}" ]; then
  kill "$PORT_PID" || true
  sleep 2
fi

# Fallback: stop any lingering spring-boot:run launcher
PIDS=$(ps -ef | rg "mvn .*spring-boot:run" | rg -v rg | awk '{print $2}' || true)
if [ -n "${PIDS:-}" ]; then
  kill $PIDS || true
  sleep 2
fi

# Detach fully so the JVM survives shell/session teardown.
if command -v setsid >/dev/null 2>&1; then
  setsid /bin/bash -lc "export APP_BASE_URL='${APP_BASE_URL}'; exec mvn -DskipMinify=true spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--server.port=${PORT}" \
    </dev/null > "$LOG_FILE" 2>&1 &
else
  APP_BASE_URL="${APP_BASE_URL}" nohup mvn -DskipMinify=true spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--server.port=${PORT} \
    </dev/null > "$LOG_FILE" 2>&1 &
fi
NEW_PID=$!
disown "$NEW_PID" 2>/dev/null || true
echo "$NEW_PID" > "$PID_FILE"

sleep 2
tail -n 12 "$LOG_FILE" || true
