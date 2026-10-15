# otter

A real-time collaborative plain-text editor built on a **hand-written Operational Transformation
(OT) engine** — no Yjs, ShareDB, or Automerge. Two people edit the same document at once; concurrent
edits are transformed so every client converges on byte-identical text.

Java 21 · Spring WebFlux (reactive, raw WebSockets) · PostgreSQL (R2DBC) · React + TypeScript.

<!-- Record this: two browser windows typing into the same document at the same time. -->
<!-- See "Recording the demo" below. -->
![Two windows editing one document and converging](docs/demo.gif)

## What it demonstrates

- **Operational Transformation**, server-centric (Jupiter model): one sequencer per document assigns
  a gapless revision to every operation; clients run a three-state machine (Synchronized /
  AwaitingAck / AwaitingWithBuffer) with at most one operation in flight.
- **Convergence under concurrency**, checked by property tests (jqwik) asserting the TP1 property at
  up to 1,000,000 generated cases.
- **Durability via event sourcing**: the document is a fold over an append-only operation log in
  Postgres, with periodic snapshots and retention compaction.
- **Reconnection without lost work**: drop the network mid-edit; on restore the client replays missed
  server operations and re-sends its own pending edits idempotently.
- **Presence and remote cursors** that shift correctly as the document changes underneath them.

The design rationale lives in [`DESIGN.md`](DESIGN.md); how the code is built lives in
[`CLAUDE.md`](CLAUDE.md).

## Architecture

```
React client (textarea + OT state machine)
      │  WebSocket, JSON frames
      ▼
Spring WebFlux handler  ──►  DocumentSession (actor: one command loop per document)
                                   │  synchronous append before broadcast
                                   ▼
                             PostgreSQL  (operations log + snapshots)
```

One `DocumentSession` per document is the single writer; it serialises commands on a reactive
mailbox, persists each operation before broadcasting the acknowledgement, and rebuilds itself from
the log on first access. Scaling to multiple instances (routing connections by document id) is
designed in `DESIGN.md §8` but intentionally not deployed — a single instance is correct; two
without affinity routing diverge.

## Run it locally

```bash
docker compose up -d postgres                 # Postgres 16
./gradlew :server:bootRun                      # backend on :8080
cd client && npm install && npm run dev        # client on :5173, proxying /ws to :8080
```

Open two browser windows at the client URL and type in both.

Full containerised stack (backend image + Postgres):

```bash
docker compose --profile app up --build
```

## Tests

```bash
./gradlew build                                # unit + Testcontainers integration tests
./gradlew :ot-core:test -Djqwik.tries.default=1000000   # property tests at full strength (slow)
cd client && npm run test                      # client OT, convergence, cursor, reconnection tests
```

## Deploy (single instance, free tier)

The hosted target is a static client on a CDN plus one backend instance, backed by Neon Postgres.
**Deploy exactly one backend instance** — see `DESIGN.md §7`.

1. **Database — [Neon](https://neon.tech) free.** Create a project; copy the connection string. Do
   **not** use Render's free Postgres (it expires after 30 days and the op log is the source of truth).
2. **Backend — [Render](https://render.com) free, Docker.** `render.yaml` is a blueprint; set these
   environment variables in the dashboard (from your Neon database):
   - `OTTER_R2DBC_URL` — `r2dbc:postgresql://<host>/<db>?sslmode=require`
   - `OTTER_JDBC_URL` — `jdbc:postgresql://<host>/<db>?sslmode=require`
   - `OTTER_DB_USER`, `OTTER_DB_PASSWORD`
3. **Client — Netlify or Cloudflare Pages.** `netlify.toml` is included. Set `VITE_WS_URL` to
   `wss://<your-service>.onrender.com/ws` so the built client connects to the backend.
4. **Keep-warm (optional).** Set the repo variable `HEALTHCHECK_URL` to the backend's
   `/actuator/health`; the `keepwarm` workflow pings it every 10 minutes.

**Cold starts.** A free Render service sleeps after 15 minutes idle and takes 30–60s to wake. The
client shows a banner and reconnects automatically — but the demo GIF above is what most visitors
actually see, so it is the highest-value deployment artifact.

### Recording the demo

1. Run the stack locally (two browser windows side by side on the same document).
2. Record a short screen capture of simultaneous typing converging in both windows.
3. Save it as `docs/demo.gif` (keep it small; trim to ~10 seconds).

## Status

Milestones M1–M7 are complete: OT engine, server control loop, client state machine, Postgres
persistence with snapshots, Docker and CI, cursors and presence, and reconnection with op replay.
M8 (multi-instance affinity routing) is designed but optional.
