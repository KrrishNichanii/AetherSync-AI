# AetherSync AI — Confluence Knowledge Base Sync

AetherSync AI keeps the pgvector store in sync with a Confluence Cloud space (default `MFS` on
`https://vector11.atlassian.net/wiki`) and answers questions with clickable citations back to the
original Confluence pages. Manual file uploads keep working alongside the synced content.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `CONFLUENCE_BASE_URL` | `https://vector11.atlassian.net/wiki` | Wiki root (include `/wiki`) |
| `CONFLUENCE_USERNAME` | — | Atlassian account email (Basic auth) |
| `CONFLUENCE_API_TOKEN` | — | Atlassian API token ([create one](https://id.atlassian.com/manage-profile/security/api-tokens)) |
| `CONFLUENCE_SPACE_KEY` | `MFS` | Space to index |
| `CONFLUENCE_SYNC_ENABLED` | `true` | Enables the scheduled sync (manual triggers always work) |
| `CONFLUENCE_SYNC_CRON` | `0 0 */6 * * *` | Spring cron (sec min hour day month weekday) |
| `CONFLUENCE_SYNC_ON_STARTUP` | `false` | Run one sync when the app starts |
| `GEMINI_API_KEY` | — | Used for both chat and `gemini-embedding-001` (768-dim) embeddings |

Without `CONFLUENCE_USERNAME`/`CONFLUENCE_API_TOKEN` the app still starts; sync is reported as
"not configured" and only uploaded documents are searchable.

## How a sync run works

1. `GET /wiki/api/v2/spaces?keys=MFS` resolves the space id.
2. `GET /wiki/api/v2/spaces/{id}/pages?status=current&body-format=storage` lists every published page,
   following `_links.next` cursors. 429/5xx responses are retried with backoff (honouring `Retry-After`).
3. Each page is compared with `confluence_synced_pages` by **version number and SHA-256 content hash**.
   Unchanged pages are skipped (no embedding cost).
4. New/changed pages are converted from storage XHTML to Markdown (headings, lists, tables, code and
   Mermaid macros preserved), split with `TokenTextSplitter`, and embedded. Every chunk carries
   `source=confluence`, `pageId`, `pageTitle`, `spaceKey`, `url` (absolute web UI link), `pageVersion`,
   `chunkIndex` and a per-ingest `syncId`.
5. New chunks are written **before** the previous version's chunks are deleted (`syncId != new`), so a
   failed embedding never leaves a page unsearchable. A failed page keeps its old tracking row and is
   retried on the next run.
6. Pages no longer returned by Confluence (deleted/archived/unpublished) have their vectors purged —
   **only after a complete, successful listing**, so an API outage can never wipe the index.
7. Every run is recorded in `confluence_sync_runs` with counts and errors.

Only one run executes at a time; overlapping manual/scheduled triggers get HTTP 409.

## REST API

| Method & path | Access | Description |
|---|---|---|
| `POST /api/sync/confluence` | ADMIN | Start a background sync. Returns `202` with the run record. `409` if running, `503` if unconfigured. |
| `GET /api/sync/confluence/status` | signed in | Configuration, running flag, page/chunk counts, last run |
| `GET /api/sync/confluence/runs?limit=20` | signed in | Recent runs (newest first) |
| `GET /api/sync/confluence/runs/{id}` | signed in | One run including its error log |
| `GET /api/sync/confluence/pages` | signed in | Pages currently indexed |

To make a user an admin: `UPDATE users SET role = 'ADMIN' WHERE email = '...';` (they must log in again).

## Retrieval & citations

- Without a document filter, search covers **the user's own uploads + all Confluence chunks**. With a
  document selected, it is scoped to that upload only (per-user isolation is unchanged).
- The prompt labels each chunk `[Source N]` with its title and URL and instructs the model to cite
  Confluence facts as Markdown links using only URLs present in the context.
- Chat and search responses include `citations` (per chunk, now with `sourceType`, `sourceTitle`,
  `sourceUrl`, `pageId`) and `sources` (de-duplicated per page, numbered like the prompt).
- The UI renders sources as chips linking to Confluence, and renders ```` ```mermaid ```` blocks as
  live diagrams (zoom, fullscreen, view/copy source).
