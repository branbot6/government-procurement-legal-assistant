#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
LOG_FILE="$RUN_DIR/legal-assistant.log"
PID_FILE="$RUN_DIR/legal-assistant.pid"
ENV_FILE="$ROOT_DIR/.env"

mkdir -p "$RUN_DIR"

load_env() {
  if [[ -f "$ENV_FILE" ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ "$line" =~ ^[[:space:]]*# ]] && continue
      [[ "$line" =~ ^[[:space:]]*$ ]] && continue
      [[ "$line" != *"="* ]] && continue

      local key="${line%%=*}"
      local value="${line#*=}"
      key="$(echo "$key" | tr -d '[:space:]')"
      value="$(echo "$value" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"

      if [[ ( "${value:0:1}" == "'" && "${value: -1}" == "'" ) || ( "${value:0:1}" == "\"" && "${value: -1}" == "\"" ) ]]; then
        value="${value:1:${#value}-2}"
      fi

        case "$key" in
        MINIMAX_API_KEY|API_KEY|APP_MINIMAX_BASE_URL|APP_MINIMAX_MODEL|APP_MINIMAX_GROUP_ID|APP_PORT|SKIP_INGEST|APP_CORPUS_ROOT_PATH|APP_PDF_OCR_FALLBACK_ENABLED|APP_PDF_OCR_LANG)
          export "$key=$value"
          ;;
        APP_UI_INVITE_CODE)
          export "$key=$value"
          ;;
      esac
    done < "$ENV_FILE"
  fi

  if [[ -n "${API_KEY:-}" && -z "${MINIMAX_API_KEY:-}" ]]; then
    MINIMAX_API_KEY="$API_KEY"
  fi

  : "${APP_PORT:=8081}"
  : "${SKIP_INGEST:=true}"
  : "${APP_CORPUS_ROOT_PATH:=/Users/brandonbot/APPdev/政策法规2.20}"
  : "${APP_PDF_OCR_FALLBACK_ENABLED:=true}"
  : "${APP_PDF_OCR_LANG:=chi_sim+eng}"
  : "${APP_MINIMAX_BASE_URL:=https://api.minimax.io/v1}"
  : "${APP_MINIMAX_MODEL:=MiniMax-M2.5}"
  : "${APP_MINIMAX_GROUP_ID:=}"
  : "${APP_UI_INVITE_CODE:=LEGAL-2026}"
}

check_ocr_env() {
  if [[ "${APP_PDF_OCR_FALLBACK_ENABLED}" != "true" ]]; then
    return 0
  fi

  if ! command -v ocrmypdf >/dev/null 2>&1; then
    echo "警告: APP_PDF_OCR_FALLBACK_ENABLED=true 但未安装 ocrmypdf。扫描稿将难以入库。"
    return 0
  fi

  if ! command -v tesseract >/dev/null 2>&1; then
    echo "警告: 已安装 ocrmypdf 但未安装 tesseract。扫描稿 OCR 可能失败。"
    return 0
  fi

  local langs
  langs="$(tesseract --list-langs 2>/dev/null || true)"
  if [[ "$APP_PDF_OCR_LANG" == *"chi_sim"* ]] && ! echo "$langs" | grep -q "^chi_sim$"; then
    echo "警告: 未检测到 tesseract 中文语言包 chi_sim，中文扫描件 OCR 质量会明显下降。"
  fi
}

require_mvn() {
  if ! command -v mvn >/dev/null 2>&1; then
    echo "mvn 未安装，请先执行: brew install maven"
    exit 1
  fi
}

resolve_java_bin() {
  if command -v java >/dev/null 2>&1; then
    local java_cmd
    java_cmd="$(command -v java)"
    if "$java_cmd" -version >/dev/null 2>&1; then
      JAVA_BIN="$java_cmd"
      return 0
    fi
  fi

  local runtime
  runtime="$(mvn -v 2>/dev/null | awk -F'runtime: ' '/runtime:/ {print $2; exit}')"
  if [[ -n "$runtime" && -x "$runtime/bin/java" ]]; then
    JAVA_BIN="$runtime/bin/java"
    return 0
  fi

  local java_home
  java_home="$(/usr/libexec/java_home 2>/dev/null || true)"
  if [[ -n "$java_home" && -x "$java_home/bin/java" ]]; then
    JAVA_BIN="$java_home/bin/java"
    return 0
  fi

  echo "未找到可用 java 运行时。请安装 JDK 或将 java 加入 PATH。"
  exit 1
}

running_pid() {
  if [[ ! -f "$PID_FILE" ]]; then
    return 1
  fi

  local pid
  pid="$(cat "$PID_FILE")"
  if [[ -z "$pid" ]]; then
    return 1
  fi

  if kill -0 "$pid" >/dev/null 2>&1; then
    echo "$pid"
    return 0
  fi

  rm -f "$PID_FILE"
  return 1
}

wait_for_health() {
  local retries=40
  local i
  for ((i=1; i<=retries; i++)); do
    if curl -fsS "http://localhost:${APP_PORT}/api/v1/healthz" >/dev/null 2>&1; then
      echo "服务已就绪: http://localhost:${APP_PORT}/api/v1/healthz"
      return 0
    fi
    sleep 1
  done
  echo "服务启动超时，请查看日志: $LOG_FILE"
  return 1
}

start() {
  load_env
  require_mvn
  resolve_java_bin
  check_ocr_env

  if [[ -z "${MINIMAX_API_KEY:-}" ]]; then
    echo "警告: 未设置 MINIMAX_API_KEY，将仅可使用 UI/项目管理，问答会返回鉴权提示。"
  fi

  if running_pid >/dev/null; then
    local pid
    pid="$(running_pid)"
    echo "服务已在运行, PID=$pid"
    echo "健康检查: http://localhost:${APP_PORT}/api/v1/healthz"
    exit 0
  fi

  local spring_args="--server.port=${APP_PORT}"
  if [[ "${SKIP_INGEST}" == "true" ]]; then
    spring_args="${spring_args} --skipIngest"
  fi

  (
    cd "$ROOT_DIR"
    mvn -q -DskipTests package
  )

  local jar_file
  jar_file="$(ls -1 "$ROOT_DIR"/target/*.jar 2>/dev/null | grep -v 'original' | head -n 1 || true)"
  if [[ -z "$jar_file" ]]; then
    echo "未找到可运行 JAR，请检查打包是否成功。"
    exit 1
  fi

  (
    cd "$ROOT_DIR"
    if command -v setsid >/dev/null 2>&1; then
      nohup setsid env \
        MINIMAX_API_KEY="${MINIMAX_API_KEY:-}" \
        APP_MINIMAX_BASE_URL="$APP_MINIMAX_BASE_URL" \
        APP_MINIMAX_MODEL="$APP_MINIMAX_MODEL" \
        APP_MINIMAX_GROUP_ID="$APP_MINIMAX_GROUP_ID" \
        APP_UI_INVITE_CODE="$APP_UI_INVITE_CODE" \
        APP_CORPUS_ROOT_PATH="$APP_CORPUS_ROOT_PATH" \
        APP_PDF_OCR_FALLBACK_ENABLED="$APP_PDF_OCR_FALLBACK_ENABLED" \
        APP_PDF_OCR_LANG="$APP_PDF_OCR_LANG" \
        "$JAVA_BIN" -jar "$jar_file" $spring_args \
        >"$LOG_FILE" 2>&1 < /dev/null &
    else
      nohup env \
        MINIMAX_API_KEY="${MINIMAX_API_KEY:-}" \
        APP_MINIMAX_BASE_URL="$APP_MINIMAX_BASE_URL" \
        APP_MINIMAX_MODEL="$APP_MINIMAX_MODEL" \
        APP_MINIMAX_GROUP_ID="$APP_MINIMAX_GROUP_ID" \
        APP_UI_INVITE_CODE="$APP_UI_INVITE_CODE" \
        APP_CORPUS_ROOT_PATH="$APP_CORPUS_ROOT_PATH" \
        APP_PDF_OCR_FALLBACK_ENABLED="$APP_PDF_OCR_FALLBACK_ENABLED" \
        APP_PDF_OCR_LANG="$APP_PDF_OCR_LANG" \
        "$JAVA_BIN" -jar "$jar_file" $spring_args \
        >"$LOG_FILE" 2>&1 < /dev/null &
    fi
    echo $! >"$PID_FILE"
  )

  local pid
  pid="$(cat "$PID_FILE")"
  echo "启动中, PID=$pid"
  wait_for_health
}

stop() {
  if ! running_pid >/dev/null; then
    echo "服务未运行"
    return 0
  fi

  local pid
  pid="$(running_pid)"
  kill "$pid" >/dev/null 2>&1 || true

  local retries=15
  local i
  for ((i=1; i<=retries; i++)); do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      rm -f "$PID_FILE"
      echo "服务已停止"
      return 0
    fi
    sleep 1
  done

  kill -9 "$pid" >/dev/null 2>&1 || true
  rm -f "$PID_FILE"
  echo "服务已强制停止"
}

status() {
  load_env
  if running_pid >/dev/null; then
    local pid
    pid="$(running_pid)"
    echo "运行中, PID=$pid"
    echo "端口: ${APP_PORT}"
    echo "健康检查: http://localhost:${APP_PORT}/api/v1/healthz"
  else
    echo "未运行"
  fi
}

logs() {
  if [[ -f "$LOG_FILE" ]]; then
    tail -n 120 -f "$LOG_FILE"
  else
    echo "暂无日志: $LOG_FILE"
  fi
}

ingest() {
  load_env
  if ! curl -fsS "http://localhost:${APP_PORT}/api/v1/healthz" >/dev/null 2>&1; then
    echo "服务未运行，自动启动..."
    start
  fi
  curl -sS -X POST "http://localhost:${APP_PORT}/api/v1/ingest/sync"
  echo
}

ask() {
  load_env
  if ! curl -fsS "http://localhost:${APP_PORT}/api/v1/healthz" >/dev/null 2>&1; then
    echo "服务未运行，自动启动..."
    start
  fi

  local raw_output="false"
  if [[ "${1:-}" == "--raw" ]]; then
    raw_output="true"
    shift
  fi

  local question="${1:-}"
  if [[ -z "$question" ]]; then
    echo "用法: ./scripts/app.sh ask \"你的问题\""
    echo "     ./scripts/app.sh ask --raw \"你的问题\""
    exit 1
  fi

  local response
  response="$(curl -sS -X POST "http://localhost:${APP_PORT}/api/v1/chat/ask" \
    -H 'Content-Type: application/json' \
    -d "{\"question\":\"${question}\"}")"

  if [[ "$raw_output" == "true" ]]; then
    echo "$response"
    echo
    return 0
  fi

  if command -v jq >/dev/null 2>&1; then
    echo "$response" | jq -r '.answer // .'
  else
    echo "$response"
  fi
  echo
}

usage() {
  cat <<USAGE
用法: ./scripts/app.sh <command>

commands:
  start     启动服务
  stop      停止服务
  restart   重启服务
  status    查看状态
  logs      查看日志
  ingest    手动入库
  ask       发起问答请求（默认只输出答案，--raw 输出完整JSON）
USAGE
}

main() {
  local cmd="${1:-}"
  case "$cmd" in
    start)
      start
      ;;
    stop)
      stop
      ;;
    restart)
      stop || true
      start
      ;;
    status)
      status
      ;;
    logs)
      logs
      ;;
    ingest)
      ingest
      ;;
    ask)
      shift
      ask "$*"
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
