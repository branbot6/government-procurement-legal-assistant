#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/embedding_profile.sh local-ollama
  ./scripts/embedding_profile.sh thirdparty <base_url> <model> [endpoint]
  ./scripts/embedding_profile.sh disable

Examples:
  ./scripts/embedding_profile.sh local-ollama
  ./scripts/embedding_profile.sh thirdparty https://api.example.com text-embedding-3-large /v1/embeddings
EOF
}

ensure_env_file() {
  if [[ ! -f "$ENV_FILE" ]]; then
    if [[ -f "$ROOT_DIR/.env.example" ]]; then
      cp "$ROOT_DIR/.env.example" "$ENV_FILE"
    else
      touch "$ENV_FILE"
    fi
  fi
}

set_kv() {
  local key="$1"
  local value="$2"
  if rg -n "^${key}=" "$ENV_FILE" >/dev/null 2>&1; then
    sed -i '' "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

ensure_env_file
cp "$ENV_FILE" "${ENV_FILE}.bak.$(date +%Y%m%d-%H%M%S)"

case "${1:-}" in
  local-ollama)
    set_kv "APP_EMBEDDING_ENABLED" "true"
    set_kv "APP_EMBEDDING_BASE_URL" "http://127.0.0.1:11434"
    set_kv "APP_EMBEDDING_ENDPOINT" "/v1/embeddings"
    set_kv "APP_EMBEDDING_MODEL" "hf.co/CompendiumLabs/bge-large-zh-v1.5-gguf"
    set_kv "APP_EMBEDDING_TIMEOUT_SECONDS" "25"
    set_kv "APP_EMBEDDING_BATCH_SIZE" "32"
    echo "Switched embedding profile to local-ollama"
    ;;
  thirdparty)
    base_url="${2:-}"
    model="${3:-}"
    endpoint="${4:-/v1/embeddings}"
    if [[ -z "$base_url" || -z "$model" ]]; then
      usage
      exit 1
    fi
    set_kv "APP_EMBEDDING_ENABLED" "true"
    set_kv "APP_EMBEDDING_BASE_URL" "$base_url"
    set_kv "APP_EMBEDDING_ENDPOINT" "$endpoint"
    set_kv "APP_EMBEDDING_MODEL" "$model"
    echo "Switched embedding profile to thirdparty"
    ;;
  disable)
    set_kv "APP_EMBEDDING_ENABLED" "false"
    echo "Embedding disabled"
    ;;
  *)
    usage
    exit 1
    ;;
esac

echo "Updated: $ENV_FILE"
rg -n "^APP_EMBEDDING_(ENABLED|BASE_URL|ENDPOINT|MODEL|TIMEOUT_SECONDS|BATCH_SIZE)=" "$ENV_FILE" -S || true
