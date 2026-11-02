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

# Fallback: stop any lingering spring-boot:run launcher.
#
# This used to shell out to `rg`, which is not installed everywhere, and `set -e` does not
# catch it because the `|| true` swallows the 127: the fallback silently found nothing and a
# stuck launcher kept the port. pgrep is in procps and is here anyway, two lines up.
#
# pgrep -f matches whole command lines, so it also matches any shell whose own line happens to
# mention the command — including whatever invoked this script. Killing that is how the `rg`
# version's sibling bug went: a start command that kills itself exits 144 with an empty log and
# nothing bound to the port, and it reads as the server dying at startup. So skip this process
# and every one of its ancestors; a launcher this script should stop is never among them.
self_and_forebears() {
  local p=$1
  while [ -n "$p" ] && [ "$p" -gt 1 ] 2>/dev/null; do
    printf ' %s' "$p"
    p=$(ps -o ppid= -p "$p" 2>/dev/null | tr -d ' ')
  done
  printf ' '
}
MINE=$(self_and_forebears $$)
PIDS=""
for p in $(pgrep -f "mvn .*spring-boot:run" || true); do
  case "$MINE" in *" $p "*) continue ;; esac
  PIDS="${PIDS:+$PIDS }$p"
done
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
