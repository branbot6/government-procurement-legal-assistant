#!/bin/sh
set -eu

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

# Persist runtime files (H2 DB / vector-store / logs) to Render disk.
# On local/dev machine (no /var/data permission), fallback to project .run.
DATA_ROOT="${RENDER_DISK_PATH:-/var/data}"
RUN_TARGET="$DATA_ROOT/.run"
if ! mkdir -p "$RUN_TARGET" 2>/dev/null; then
  RUN_TARGET="$ROOT_DIR/.run"
  mkdir -p "$RUN_TARGET"
fi

if [ "$RUN_TARGET" != "$ROOT_DIR/.run" ]; then
  if [ -e .run ] && [ ! -L .run ]; then
    rm -rf .run
  fi
  ln -sfn "$RUN_TARGET" .run
else
  mkdir -p .run
fi

# Seed vector store on first boot so QA works even before uploading corpus to /var/data/corpus.
SEED_VECTOR_FILE="$ROOT_DIR/seed/vector-store.json"
TARGET_VECTOR_FILE="$RUN_TARGET/vector-store.json"
CORPUS_ROOT="${APP_CORPUS_ROOT_PATH:-$DATA_ROOT/corpus}"
TARGET_VECTOR_BYTES=0
if [ -f "$TARGET_VECTOR_FILE" ]; then
  TARGET_VECTOR_BYTES="$(wc -c < "$TARGET_VECTOR_FILE" | tr -d '[:space:]' || echo 0)"
fi
if [ -f "$SEED_VECTOR_FILE" ] && { [ ! -f "$TARGET_VECTOR_FILE" ] || [ "${TARGET_VECTOR_BYTES:-0}" -lt 1024 ] || [ ! -d "$CORPUS_ROOT" ]; }; then
  cp "$SEED_VECTOR_FILE" "$TARGET_VECTOR_FILE"
fi

JAR_FILE="$(ls -1 target/*.jar 2>/dev/null | grep -v 'original' | head -n 1 || true)"
if [ -z "$JAR_FILE" ]; then
  echo "未找到可运行 JAR，请确认 buildCommand 已成功执行。"
  exit 1
fi

exec java -Dserver.port="${PORT:-8080}" -jar "$JAR_FILE" --skipIngest
