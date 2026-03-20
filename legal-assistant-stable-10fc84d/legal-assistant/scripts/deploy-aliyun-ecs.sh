#!/usr/bin/env bash
set -euo pipefail

# One-click deploy for Alibaba Cloud ECS.
# Run this script on the ECS server as root:
#   GH_REPO=branbot6/legal-assistant-stable \
#   GH_PAT=ghp_xxx \
#   MINIMAX_API_KEY=xxx \
#   bash scripts/deploy-aliyun-ecs.sh

: "${GH_REPO:=branbot6/legal-assistant-stable}"
: "${APP_NAME:=legal-assistant}"
: "${APP_PORT:=8080}"
: "${APP_UI_INVITE_ENABLED:=false}"
: "${APP_PDF_OCR_FALLBACK_ENABLED:=true}"
: "${APP_PDF_OCR_LANG:=chi_sim+eng}"
: "${APP_MINIMAX_BASE_URL:=https://api.minimax.io/v1}"
: "${APP_MINIMAX_MODEL:=MiniMax-M2.5}"
: "${APP_MINIMAX_GROUP_ID:=}"

if [[ -z "${GH_PAT:-}" ]]; then
  echo "ERROR: GH_PAT is required for cloning private repo ${GH_REPO}."
  exit 1
fi

if [[ -z "${MINIMAX_API_KEY:-}" ]]; then
  echo "ERROR: MINIMAX_API_KEY is required."
  exit 1
fi

if ! command -v git >/dev/null 2>&1; then
  echo "Installing git..."
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -y
    apt-get install -y git
  else
    echo "ERROR: Unsupported OS package manager. Install git manually."
    exit 1
  fi
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Installing Docker..."
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -y
    apt-get install -y ca-certificates curl gnupg lsb-release
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
    echo \
      "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
      $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | tee /etc/apt/sources.list.d/docker.list >/dev/null
    apt-get update -y
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  else
    echo "ERROR: Unsupported OS package manager. Install Docker manually."
    exit 1
  fi
fi

WORKDIR="/opt/${APP_NAME}"
DATADIR="/opt/${APP_NAME}-data"
mkdir -p "${WORKDIR}" "${DATADIR}/corpus" "${DATADIR}/.run"

if [[ ! -d "${WORKDIR}/.git" ]]; then
  rm -rf "${WORKDIR}"
  git clone "https://${GH_PAT}@github.com/${GH_REPO}.git" "${WORKDIR}"
else
  git -C "${WORKDIR}" remote set-url origin "https://${GH_PAT}@github.com/${GH_REPO}.git"
  git -C "${WORKDIR}" fetch origin main
  git -C "${WORKDIR}" checkout main
  git -C "${WORKDIR}" pull --ff-only origin main
fi

cd "${WORKDIR}/legal-assistant"
docker build -t "${APP_NAME}:latest" .

docker rm -f "${APP_NAME}" >/dev/null 2>&1 || true
docker run -d \
  --name "${APP_NAME}" \
  --restart unless-stopped \
  -p "${APP_PORT}:8080" \
  -e PORT=8080 \
  -e APP_UI_INVITE_ENABLED="${APP_UI_INVITE_ENABLED}" \
  -e APP_CORPUS_ROOT_PATH="/var/data/corpus" \
  -e APP_PDF_OCR_FALLBACK_ENABLED="${APP_PDF_OCR_FALLBACK_ENABLED}" \
  -e APP_PDF_OCR_LANG="${APP_PDF_OCR_LANG}" \
  -e MINIMAX_API_KEY="${MINIMAX_API_KEY}" \
  -e APP_MINIMAX_BASE_URL="${APP_MINIMAX_BASE_URL}" \
  -e APP_MINIMAX_MODEL="${APP_MINIMAX_MODEL}" \
  -e APP_MINIMAX_GROUP_ID="${APP_MINIMAX_GROUP_ID}" \
  -v "${DATADIR}:/var/data" \
  "${APP_NAME}:latest"

sleep 5
if command -v curl >/dev/null 2>&1; then
  curl -fsS "http://127.0.0.1:${APP_PORT}/api/v1/healthz" && echo
else
  echo "Deployment completed. Install curl then verify: http://127.0.0.1:${APP_PORT}/api/v1/healthz"
fi

echo "Done."
echo "UI: http://<ECS_PUBLIC_IP>:${APP_PORT}/"
