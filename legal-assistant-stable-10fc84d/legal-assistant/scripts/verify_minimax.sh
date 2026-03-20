#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "未找到 .env: $ENV_FILE"
  exit 1
fi

while IFS= read -r line || [[ -n "$line" ]]; do
  [[ "$line" =~ ^[[:space:]]*# ]] && continue
  [[ "$line" =~ ^[[:space:]]*$ ]] && continue
  [[ "$line" != *"="* ]] && continue

  key="${line%%=*}"
  value="${line#*=}"
  key="$(echo "$key" | tr -d '[:space:]')"
  value="$(echo "$value" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"

  if [[ ( "${value:0:1}" == "'" && "${value: -1}" == "'" ) || ( "${value:0:1}" == "\"" && "${value: -1}" == "\"" ) ]]; then
    value="${value:1:${#value}-2}"
  fi

  case "$key" in
    MINIMAX_API_KEY|APP_MINIMAX_BASE_URL|APP_MINIMAX_MODEL|APP_MINIMAX_GROUP_ID)
      export "$key=$value"
      ;;
  esac
done < "$ENV_FILE"

if [[ -z "${MINIMAX_API_KEY:-}" ]]; then
  echo "MINIMAX_API_KEY 未配置"
  exit 1
fi

BASE_URL="${APP_MINIMAX_BASE_URL:-https://api.minimax.io/v1}"
MODEL="${APP_MINIMAX_MODEL:-MiniMax-M2.5}"
GROUP_ID="${APP_MINIMAX_GROUP_ID:-}"
PROMPT="${1:-ping}"

TMP_BODY="$(mktemp)"
TMP_CODE="$(mktemp)"
trap 'rm -f "$TMP_BODY" "$TMP_CODE"' EXIT

if [[ -n "$GROUP_ID" ]]; then
  # Legacy/native endpoint requiring GroupId.
  curl -sS \
    -o "$TMP_BODY" \
    -w "%{http_code}" \
    "${BASE_URL}/text/chatcompletion_v2?GroupId=${GROUP_ID}" \
    -H "Authorization: Bearer ${MINIMAX_API_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"model\":\"${MODEL}\",\"messages\":[{\"role\":\"system\",\"name\":\"MiniMax AI\"},{\"role\":\"user\",\"name\":\"user\",\"content\":\"${PROMPT}\"}]}" \
    > "$TMP_CODE"
else
  # OpenAI-compatible endpoint.
  curl -sS \
    -o "$TMP_BODY" \
    -w "%{http_code}" \
    "${BASE_URL}/chat/completions" \
    -H "Authorization: Bearer ${MINIMAX_API_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"model\":\"${MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"${PROMPT}\"}]}" \
    > "$TMP_CODE"
fi

HTTP_CODE="$(cat "$TMP_CODE")"
echo "HTTP=${HTTP_CODE}"

if [[ "$HTTP_CODE" == "200" ]]; then
  echo "MiniMax API 可达，检查业务状态..."
  # 尝试提取最小文本输出；不依赖 jq
  python3 - "$TMP_BODY" <<'PY' || PY_RC=$?
import json,sys
p=sys.argv[1]
try:
    data=json.load(open(p,'r',encoding='utf-8'))
    base=(data.get("base_resp") or {})
    if base:
        code=base.get("status_code")
        msg=base.get("status_msg")
        if code and code != 0:
            print(f"base_resp.status_code={code} msg={msg}")
            sys.exit(2)
    msg=(data.get("choices") or [{}])[0].get("message",{}).get("content")
    if not msg:
        msg=data.get("reply") or ""
    if msg:
        print("reply:", msg[:120].replace("\n"," "))
    else:
        print("reply: <empty>")
except Exception:
    print("reply: <parse-failed>")
PY
  PY_RC="${PY_RC:-0}"
  if [[ "$PY_RC" -eq 2 ]]; then
    echo "MiniMax API 不可用（业务层返回错误）"
    cat "$TMP_BODY"
    exit 2
  fi
  echo "MiniMax API 可用"
  exit 0
fi

echo "MiniMax API 不可用"
cat "$TMP_BODY"
exit 2
