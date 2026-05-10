#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/logs"
RUN_DIR="$ROOT_DIR/.run"
SPRING_PID_FILE="$RUN_DIR/spring.pid"
BOT_PID_FILE="$RUN_DIR/bot.pid"
SPRING_LOG="$LOG_DIR/spring.log"
BOT_LOG="$LOG_DIR/bot.log"

mkdir -p "$LOG_DIR" "$RUN_DIR"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

load_env_file() {
  local env_file="$1"
  [[ -f "$env_file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "${line:0:1}" == "#" ]] && continue
    if [[ "$line" == *=* ]]; then
      local key="${line%%=*}"
      local value="${line#*=}"
      key="${key#"${key%%[![:space:]]*}"}"
      key="${key%"${key##*[![:space:]]}"}"
      value="${value#"${value%%[![:space:]]*}"}"
      value="${value%"${value##*[![:space:]]}"}"
      if [[ "$value" == \"*\" && "$value" == *\" ]]; then
        value="${value:1:${#value}-2}"
      elif [[ "$value" == \'*\' && "$value" == *\' ]]; then
        value="${value:1:${#value}-2}"
      fi
      [[ -z "$key" ]] && continue
      export "$key=$value"
    fi
  done <"$env_file"
}

is_running() {
  local pid_file="$1"
  if [[ -f "$pid_file" ]]; then
    local pid
    pid="$(cat "$pid_file" || true)"
    [[ -n "${pid:-}" ]] && kill -0 "$pid" >/dev/null 2>&1
    return $?
  fi
  return 1
}

start_db() {
  require_cmd docker
  if docker compose version >/dev/null 2>&1; then
    (cd "$ROOT_DIR" && docker compose up -d db)
  else
    require_cmd docker-compose
    (cd "$ROOT_DIR" && docker-compose up -d db)
  fi
}

start_spring() {
  if is_running "$SPRING_PID_FILE"; then
    echo "Spring already running (pid $(cat "$SPRING_PID_FILE"))."
    return
  fi

  (
    cd "$ROOT_DIR"
    load_env_file "$ROOT_DIR/.env"
    local bind_address="${SPRING_BIND_ADDRESS:-${SERVER_ADDRESS:-0.0.0.0}}"
    local spring_port="${SPRING_PORT:-${SERVER_PORT:-8080}}"
    : >"$SPRING_LOG"
    ./gradlew --no-daemon bootJar >>"$SPRING_LOG" 2>&1
    local app_jar
    app_jar="$(find "$ROOT_DIR/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*plain.jar' | sort | tail -n 1)"
    [[ -n "$app_jar" ]] || { echo "No Spring Boot jar found in $ROOT_DIR/build/libs" >>"$SPRING_LOG"; exit 1; }
    nohup setsid java -jar "$app_jar" --server.address="${bind_address}" --server.port="${spring_port}" \
      >>"$SPRING_LOG" 2>&1 < /dev/null &
    echo $! >"$SPRING_PID_FILE"
  )
  echo "Spring started (pid $(cat "$SPRING_PID_FILE"))."
}

start_bot() {
  if is_running "$BOT_PID_FILE"; then
    echo "Bot already running (pid $(cat "$BOT_PID_FILE"))."
    return
  fi

  (
    cd "$ROOT_DIR/bot"
    nohup setsid env -i \
      HOME="${HOME:-}" \
      PATH="${PATH:-/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}" \
      USER="${USER:-ubuntu}" \
      node src/WhatsappBot.js >"$BOT_LOG" 2>&1 &
    echo $! >"$BOT_PID_FILE"
  )
  echo "Bot started (pid $(cat "$BOT_PID_FILE"))."
}

stop_proc() {
  local name="$1"
  local pid_file="$2"
  if is_running "$pid_file"; then
    local pid
    pid="$(cat "$pid_file")"
    kill "$pid" >/dev/null 2>&1 || true
    sleep 1
    if kill -0 "$pid" >/dev/null 2>&1; then
      kill -9 "$pid" >/dev/null 2>&1 || true
    fi
    rm -f "$pid_file"
    echo "$name stopped."
  else
    rm -f "$pid_file"
    echo "$name not running."
  fi
}

status_proc() {
  local name="$1"
  local pid_file="$2"
  if is_running "$pid_file"; then
    echo "$name: running (pid $(cat "$pid_file"))"
  else
    echo "$name: stopped"
  fi
}

show_logs() {
  local target="${1:-all}"
  case "$target" in
    spring) tail -n 120 -f "$SPRING_LOG" ;;
    bot) tail -n 120 -f "$BOT_LOG" ;;
    all) tail -n 120 -f "$SPRING_LOG" "$BOT_LOG" ;;
    *) echo "Usage: $0 logs [spring|bot|all]"; exit 1 ;;
  esac
}

start_all() {
  require_cmd node
  require_cmd nohup
  require_cmd setsid
  require_cmd java
  [[ -f "$ROOT_DIR/.env" ]] || { echo "Missing $ROOT_DIR/.env"; exit 1; }
  [[ -f "$ROOT_DIR/bot/.env" ]] || { echo "Missing $ROOT_DIR/bot/.env"; exit 1; }
  load_env_file "$ROOT_DIR/.env"

  start_db
  start_spring
  start_bot

  echo ""
  echo "Startup complete."
  echo "Spring log: $SPRING_LOG"
  echo "Bot log:    $BOT_LOG"
  echo "Spring bind: ${SPRING_BIND_ADDRESS:-${SERVER_ADDRESS:-0.0.0.0}}:${SPRING_PORT:-${SERVER_PORT:-8080}}"
  echo "aaPanel reverse proxy target: http://127.0.0.1:${SPRING_PORT:-${SERVER_PORT:-8080}}"
}

stop_all() {
  stop_proc "Bot" "$BOT_PID_FILE"
  stop_proc "Spring" "$SPRING_PID_FILE"
  if docker compose version >/dev/null 2>&1; then
    (cd "$ROOT_DIR" && docker compose stop db >/dev/null 2>&1 || true)
  elif command -v docker-compose >/dev/null 2>&1; then
    (cd "$ROOT_DIR" && docker-compose stop db >/dev/null 2>&1 || true)
  fi
  echo "DB stop requested."
}

show_status() {
  load_env_file "$ROOT_DIR/.env"
  status_proc "Spring" "$SPRING_PID_FILE"
  status_proc "Bot" "$BOT_PID_FILE"
  echo "Configured Spring bind: ${SPRING_BIND_ADDRESS:-${SERVER_ADDRESS:-0.0.0.0}}:${SPRING_PORT:-${SERVER_PORT:-8080}}"
  echo "Configured public URL: ${APP_BASE_URL:-http://localhost:${SPRING_PORT:-${SERVER_PORT:-8080}}}"
  echo "Expected aaPanel proxy target: http://127.0.0.1:${SPRING_PORT:-${SERVER_PORT:-8080}}"
  echo "DB ports:"
  ss -ltnp | rg ":5432|:${SPRING_PORT:-${SERVER_PORT:-8080}}|:3001" || true
}

cmd="${1:-}"
case "$cmd" in
  start) start_all ;;
  stop) stop_all ;;
  restart) stop_all; start_all ;;
  status) show_status ;;
  logs) show_logs "${2:-all}" ;;
  *)
    cat <<EOF
Usage: $0 {start|stop|restart|status|logs [spring|bot|all]}
EOF
    exit 1
    ;;
esac
