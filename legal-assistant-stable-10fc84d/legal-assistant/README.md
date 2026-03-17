# legal-assistant (Java)

Citation-first legal assistant skeleton for large policy/regulation corpus.

## Stack
- Java 21+
- Spring Boot 3.4
- MiniMax API adapter (`LlmClient`)
- Local ingest from markdown/html/pdf
- In-memory retriever (MVP)
- OpenSearch adapter placeholder (production)

## Corpus path
Configured by default to:
`/Users/brandonbot/APPdev/政策法规2.20`

## One-click run (recommended)
1. Install Maven 3.9+:
   ```bash
   brew install maven
   ```
2. (Recommended for scanned PDFs) install OCR tools:
   ```bash
   brew install ocrmypdf tesseract
   ```
3. Init env file:
   ```bash
   cd "/Users/brandonbot/Documents/New project/legal-assistant"
   cp .env.example .env
   ```
4. Edit `.env` and set `MINIMAX_API_KEY`.
   - 如果只想先使用 UI（登录/项目/历史/导出），可暂不配置该 key。
5. Start service:
   ```bash
   ./scripts/app.sh start
   ```
6. Check status:
   ```bash
   ./scripts/app.sh status
   ```

## Switch API quickly
Edit `.env` then restart:
- `MINIMAX_API_KEY`
- `APP_MINIMAX_BASE_URL`
- `APP_MINIMAX_MODEL`

```bash
./scripts/app.sh restart
```

## Script commands
```bash
./scripts/app.sh start
./scripts/app.sh stop
./scripts/app.sh restart
./scripts/app.sh status
./scripts/app.sh logs
./scripts/app.sh ingest
./scripts/app.sh ask "江苏省政府采购履约验收有什么核心要求？"
./scripts/verify_minimax.sh
```

## APIs
- Health: `GET /api/v1/healthz`
- Manual ingest: `POST /api/v1/ingest/sync`
- Ask: `POST /api/v1/chat/ask`
- Desktop UI: `GET /` (also supports `GET /ui` and `GET /ui/index.html`)

## Open-WebUI style workspace (new)
- Register requires username/password.
- Login requires only username/password.
- Password hint can be queried from login screen by username.
- Project management: create/update/delete project.
- History management: conversation list + global history timeline.
- Theme toggle: dark / light.
- Export:
  - Conversation -> PDF / DOCX
  - Entire project -> PDF / DOCX

UI API prefix: `/api/v1/ui/*`

## Deploy on Render (overseas)
This repo includes a Render Blueprint file at repo root: `render.yaml`.

### Option A: Blueprint (recommended)
1. Push to GitHub.
2. In Render: `New +` -> `Blueprint`.
3. Select this repository.
4. Keep defaults, then set required env var:
   - `MINIMAX_API_KEY`
5. Deploy.

### Option B: Manual Web Service
If you don't use Blueprint:
- Root Directory: `legal-assistant`
- Build Command: `mvn -q -DskipTests package`
- Start Command: `./scripts/start-render.sh`
- Health Check Path: `/api/v1/healthz`

### Important
- Render disk is mounted at `/var/data` (configured in `render.yaml`).
- Runtime data is persisted via symlink `.run -> /var/data/.run`.
- Corpus path defaults to `/var/data/corpus`. Upload your corpus files there if needed.
- App UI entry:
  - `/` (recommended)
  - `/ui`
  - `/ui/index.html`

## Data persistence (DB)
- User/project/conversation/history data are stored in local H2 file DB:
  - DB file path: `.run/legal-assistant-db.mv.db`
  - Table: `ui_store` (JSON payload)
- Legacy `.run/ui-data.json` will be migrated automatically on first startup (renamed to `.run/ui-data.json.migrated`).

Manual ingest example:
```bash
curl -X POST http://localhost:8081/api/v1/ingest/sync
```

Ask example:
```bash
curl -X POST http://localhost:8081/api/v1/chat/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"江苏省政府采购履约验收有什么核心要求？"}'
```

## Next production steps
1. Replace `InMemoryVectorStore` with OpenSearch hybrid retrieval.
2. Add embedding generation and reranker.
3. Add OCR pipeline for scanned PDFs (`ocrmypdf`/`tesseract`) and merge OCR text.
4. Add policy validity metadata: effective date, repeal status, region scope.

## Ingest behavior (current)
- Ingest scans `content.md` first.
- It automatically merges text from sibling attachments and `附件/` files (`pdf/doc/docx/xls/xlsx/html/txt/md`).
- For low-text PDFs, it first tries Tika fallback, then automatically tries `ocrmypdf` when available.
- Excel files are extracted as table-like text (sheet + row format) to improve table retrieval quality.
- Vector chunks are persisted to `.run/vector-store.json` to reduce cold-start loss.

## Deploy on Alibaba Cloud ECS (private GitHub repo)
Use script: `scripts/deploy-aliyun-ecs.sh`

1. SSH to ECS (Ubuntu, root or sudo user).
2. Prepare environment variables:
   - `GH_PAT`: GitHub Personal Access Token with `repo` permission.
   - `MINIMAX_API_KEY`: MiniMax API key.
3. Run:
   ```bash
   cd /tmp
   git clone https://github.com/branbot6/legal-assistant-stable.git
   cd legal-assistant-stable/legal-assistant
   GH_REPO=branbot6/legal-assistant-stable \
   GH_PAT=YOUR_GH_PAT \
   MINIMAX_API_KEY=YOUR_MINIMAX_API_KEY \
   APP_PORT=8080 \
   bash scripts/deploy-aliyun-ecs.sh
   ```

After deployment:
- Health check: `http://<ECS_PUBLIC_IP>:8080/api/v1/healthz`
- UI: `http://<ECS_PUBLIC_IP>:8080/`
