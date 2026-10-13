# ContextLayer

A context-management layer between a codebase and an AI agent. Instead of sending a whole repository to an
LLM, ContextLayer indexes the project, finds what matters for a task, ranks it, and compresses it into a
**context pack** that fits a token budget, with the reasons for every selection. It runs fully offline:
no API key, no subscription, and an optional local model via Ollama.

```
Project ─► Index ─► Memory ─► Hybrid retrieval ─► Ranking ─► Token budget ─► Compression ─► Context pack ─► LLM
           files    git       keyword (Postgres    retrieval   knapsack       minify,        markdown +
           chunks   graph     full-text) +         + graph +   over detail    focused        explanations
           hashes   symbols   semantic (pgvector)  git signals levels         views
                    summaries
```

## Requirements
Docker, and 8 GB RAM is enough. Optional: [Ollama](https://ollama.com) for local AI summaries and answers
(`ollama pull qwen2.5-coder:3b`, about 2 GB).

## Quick start (Docker)
1. Create a root `.env`: `DB_PASSWORD=...` and `WORKSPACE_DIR=<folder containing your repos>`.
2. `docker compose up -d --build`
3. Open http://localhost:8088, add a project (path inside the container, e.g. `/workspace/my-repo`),
   click **Refresh index**, then ask a question on the **Ask** tab.

## Development (light on RAM)
- Postgres with pgvector: `docker compose up -d db`
- Backend: `cd backend && ./mvnw spring-boot:run` (needs `backend/.env` with `DB_PASSWORD`, `DB_URL`)
- Frontend: `cd frontend && npm install && npm run dev` (proxies `/api` to :8080)
- Tests: `./mvnw test`. With `CL_PG_TEST_PASSWORD` set, the Postgres integration tests also run.

## Local AI (optional, free)
Start Ollama and the **Ask** tab can also request an answer from the local model, and the **Index** tab can write
summaries with it. Everything runs on your machine. On CPU expect 5-15 tokens per second, so answers take minutes;
tasks run in the background. Answers need a token budget of 5,000 or less (an 8,192-token model window).
Settings: `contextlayer.llm.*`.

## API (all under /api/projects/{id})
| Endpoint | Purpose |
|---|---|
| `POST /refresh?force=` | index, git, graph, summaries, embeddings |
| `GET /hybrid-search?q=` | keyword + semantic, fused (RRF) |
| `GET /rank?task=` | ranked files with per-signal scores and reasons |
| `POST /pack` | context pack: stats, items, markdown |
| `GET /pack.md?task=` | the pack as markdown, ready for an agent |
| `POST /tasks` | async task: pack, optionally a local-model answer |
| `POST /summaries/refresh?generator=llm` | local-model summaries (50 files per call) |
| `GET /token-check?task=` | compare local token counts with the model's |
| `POST /evaluate` | retrieval quality on your own golden set |

## Configuration
Everything lives in `application.properties` under `contextlayer.*`: indexing, git, embedding, search weights,
ranking weights, token budget, LLM. Environment variables override them (`CONTEXTLAYER_...`).

## Security and privacy
- Binds to localhost by default; Docker publishes only the UI, on 127.0.0.1.
- Secret-bearing files are not indexed; secret-looking values are redacted from every pack and prompt.
- No data leaves your machine: the only LLM is a local Ollama server.
- Only code files are summarised, and nothing runs unless you ask.
- Project folders are mounted read-only in Docker and can be restricted with `contextlayer.projects.allowed-roots`.

## Limitations
- Imports and symbols are regex-based (Java uses a real parser), so unusual syntax can be missed.
- The `hash` embedder is lexical. Use an Ollama embedding model for semantic search.
- A 3B local model gives modest answers and may guess; treat them as drafts. Packs are the main product.
- Token counts use an OpenAI-style tokenizer. Run `/token-check` to calibrate the safety margin.
- Indexing is synchronous and holds one transaction, so very large monorepos need batching.
- No authentication: it's a single-user local tool.
