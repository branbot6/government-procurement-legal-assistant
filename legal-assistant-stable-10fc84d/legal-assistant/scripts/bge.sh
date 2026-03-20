#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
PID_FILE="$RUN_DIR/local-bge.pid"
LOG_FILE="$RUN_DIR/local-bge.log"
VENV_DIR="$ROOT_DIR/.venv-bge"
SERVER_SCRIPT="$ROOT_DIR/scripts/local_bge_embedding_server.py"
MODEL="${APP_EMBEDDING_MODEL:-BAAI/bge-large-zh-v1.5}"
PORT="${APP_EMBEDDING_PORT:-8000}"

mkdir -p "$RUN_DIR"

running_pid() {
  [[ -f "$PID_FILE" ]] || return 1
  local pid
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  [[ -n "$pid" ]] || return 1
  if kill -0 "$pid" >/dev/null 2>&1; then
    echo "$pid"
    return 0
  fi
  rm -f "$PID_FILE"
  return 1
}

start() {
  if running_pid >/dev/null; then
    echo "local-bge already running, PID=$(running_pid)"
    return 0
  fi
  if [[ ! -x "$VENV_DIR/bin/python" ]]; then
    echo "missing venv: $VENV_DIR"
    echo "create it with: uv venv .venv-bge && source .venv-bge/bin/activate && uv pip install fastapi uvicorn sentence-transformers"
    exit 1
  fi

  nohup "$VENV_DIR/bin/python" "$SERVER_SCRIPT" --model "$MODEL" --port "$PORT" >"$LOG_FILE" 2>&1 < /dev/null &
  echo $! > "$PID_FILE"
  sleep 1

  if running_pid >/dev/null; then
    echo "local-bge started, PID=$(running_pid), url=http://localhost:${PORT}"
  else
    echo "failed to start local-bge. check log: $LOG_FILE"
    exit 1
  fi
}

stop() {
  if ! running_pid >/dev/null; then
    echo "local-bge not running"
    return 0
  fi
  local pid
  pid="$(running_pid)"
  kill "$pid" >/dev/null 2>&1 || true
  sleep 1
  if kill -0 "$pid" >/dev/null 2>&1; then
    kill -9 "$pid" >/dev/null 2>&1 || true
  fi
  rm -f "$PID_FILE"
  echo "local-bge stopped"
}

status() {
  if running_pid >/dev/null; then
    echo "running, PID=$(running_pid), url=http://localhost:${PORT}"
  else
    echo "not running"
  fi
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  restart) stop; start ;;
  logs) tail -n 120 -f "$LOG_FILE" ;;
  *)
    echo "usage: $0 {start|stop|status|restart|logs}"
    exit 1
    ;;
esac
