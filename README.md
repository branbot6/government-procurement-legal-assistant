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
`./corpus` (set via `APP_CORPUS_ROOT_PATH`)

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
   cd legal-assistant
   cp .env.example .env
   ```
4. Edit `.env` and set `MINIMAX_API_KEY`.
   - 如果只想先使用 UI（登录/项目/历史/导出），可暂不配置该 key。
   - 如需问答检索，请准备语料目录并配置：
     - `APP_CORPUS_ROOT_PATH` (默认 `./corpus`)
     - `APP_FAST_MODE_ROOT_PATH` (默认 `./fast-corpus`)
   - 如需 embedding 检索（推荐）：
     - `APP_EMBEDDING_BASE_URL`
     - `APP_EMBEDDING_ENDPOINT`
     - `APP_EMBEDDING_MODEL`
5. Start service:
   ```bash
   ./scripts/app.sh start
   ```
6. Check status:
   ```bash
   ./scripts/app.sh status
   ```

## Put Your Own Legal Corpus
Clone 后把你自己的法规文件放到以下目录（可改 `.env` 覆盖）：

- `./corpus`：兼容模式 ingest 目录（支持 `md/html/pdf/doc/docx/xls/xlsx/txt` 等）
- `./fast-corpus`：快速模式目录（建议使用结构化 `md`）

仓库已内置空目录占位：
- `corpus/.gitkeep`
- `fast-corpus/.gitkeep`

推荐目录结构示例：
```text
legal-assistant/
├─ corpus/
│  ├─ 国家法规/
│  │  ├─ 政府采购法.md
│  │  └─ 实施条例.pdf
│  └─ 地方法规/
│     └─ 江苏省/
│        └─ 采购管理办法.docx
├─ fast-corpus/
│  └─ 01_结构化法规md/
│     ├─ 政府采购法_结构化.md
│     └─ 评审专家管理_结构化.md
└─ .env
```

## Ingest And Vector Build
默认 `SKIP_INGEST=true`，采用“首启自动入库”策略：

- 若 `.run/vector-store.json` 不存在或无效：启动时自动入库
- 若已有可用索引：启动时跳过入库（加快启动）

你也可以手动控制：

1. 启动后手动入库（推荐）
   ```bash
   ./scripts/app.sh start
   ./scripts/app.sh ingest
   ```
2. 启动时自动入库
   - 在 `.env` 里设置 `SKIP_INGEST=false`（每次启动都全量入库）
   - 然后执行 `./scripts/app.sh start`

入库会自动完成：
- 文本抽取
- 分块（chunk）
- 建立检索索引并持久化到 `.run/vector-store.json`

如果 embedding 服务可用（`APP_EMBEDDING_ENABLED=true` 且接口可达），问答时会自动走 embedding 融合检索；不可用时会自动回退到词法检索。

## Ask In Quick / Compat Mode
```bash
curl -X POST http://localhost:8081/api/v1/chat/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"政府采购公开招标有哪些方式？","mode":"quick"}'
```

- `mode=quick`：快速模式（读取 `./fast-corpus`）
- `mode=compat`：兼容模式（基于 ingest 后索引）
- 不传 `mode` 默认走兼容模式

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
