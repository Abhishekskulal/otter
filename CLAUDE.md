# CLAUDE.md

Instructions for Claude Code working in this repository.

Read `docs/DESIGN.md` before doing anything. It is the authoritative specification for
architecture, the wire protocol, conflict resolution, and the database schema. This file governs
*how you work*; DESIGN.md governs *what gets built*.

---

## 0. What this project is for

This is a real-time collaborative text editor built with Operational Transformation. It is a
**learning project**. Abhi is building it to understand distributed-systems consistency deeply
enough to explain it under interview pressure.

That means: **a working editor produced quickly is a failure.** The artifact is Abhi's
understanding. The code is a side effect.

Optimise every interaction for that.

---

## 1. The learning contract — read this twice

### 1.1 Protected paths — you may NOT write here

> **Amendment — 2026-10-09, Abhi's decision (supersedes the 2026-10-06 amendment).** All protected
> paths below are unblocked. Claude builds the remaining milestones — `ot-core`, server `session/`,
> client `src/ot/`, and the test assertions — and Abhi reviews. This is an eyes-open trade: the
> project shifts from "Abhi writes the core to learn it" (§0) toward "Claude builds it, Abhi reads
> and reviews." Claude still explains what it writes, verifies each milestone before moving to the
> next, and will not weaken a test to make it pass (§1.5 still holds).

```
ot-core/src/main/**          The OT engine: apply, transform, compose, NoOp handling
ot-core/src/test/**          All test assertions and jqwik properties
server/src/main/java/**/session/**      Document session, sequencer, server control loop
client/src/ot/**             Client state machine, cursor transformation
```

If a task requires a change in a protected path:

1. **Stop.**
2. Describe precisely what needs to change and why.
3. Point at the relevant section of `DESIGN.md`.
4. Wait. Do not write it, do not write "here's what it would look like", do not put it in a
   comment or a code block "for reference."

A code block containing the answer is the same as writing the answer.

### 1.2 Where you SHOULD work freely

Everything else. Gradle config, Spring wiring, DTOs and Jackson setup, R2DBC entities and
repositories, Flyway migrations, WebSocket handler plumbing, the React shell and rendering, test
*scaffolding* (class structure, generators, fixtures — never assertions), Docker, CI, scripts.

Write these well and quickly. They are not the point, and time spent here is time stolen from the
parts that are.

### 1.3 Explain before you implement

Default mode is **explain, don't write**. When a task could be either, ask.

When you do write boilerplate, follow it with at most five bullets covering only the non-obvious
parts — a framework behaviour that isn't visible in the code, a config choice with a real
alternative, a lifecycle subtlety. Do not narrate what the code plainly says.

### 1.4 When Abhi's code is wrong

Do **not** hand over the fix.

Do this instead:
- Name the smallest failing input.
- State which invariant or DESIGN.md property it violates.
- Ask one question that points at the cause.

Example of the right shape: *"`transform(D(2,4), D(3,2))` returns a `bPrime` with negative length.
Which of the four overlap cases in §5.3 is this, and what should the intersection do?"*

Example of the wrong shape: *"Here's the corrected method."*

### 1.5 Never weaken a test

If a jqwik property fails, the implementation is wrong, or the specification is wrong. Never
reduce `tries`, narrow a generator, add an exclusion, or loosen an assertion to make it pass.
Report the failing seed and shrunk case, and stop.

### 1.6 Design patterns: explain first

See §5. Every pattern has a status. **You may not apply a pattern marked `[ ] not yet explained`**
— stop and explain it first, wait for Abhi to mark it understood, then use it.

---

## 2. Tech stack

Pin exact versions at project init and record them here. Verify current stable releases before
pinning; do not assume.

### Backend

| Concern | Choice | Notes |
|---|---|---|
| Language | Java 21 LTS | Sealed interfaces + exhaustive `switch` pattern matching are load-bearing here |
| Framework | Spring Boot 3.5.x (latest 3.x) | WebFlux, **not** Spring MVC |
| Reactive | Project Reactor | `Sinks.Many`, `Schedulers.newSingle()` |
| WebSocket | Spring WebFlux `WebSocketHandler` | Raw frames. **No STOMP, no `spring-messaging`.** |
| JSON | Jackson, `ObjectMapper` with sealed-type subtype resolution | |
| Database | PostgreSQL 16+ | Local: Docker. Hosted: **Neon** free tier (see §7) |
| DB access | R2DBC (`r2dbc-postgresql`) | Blocking JDBC on a WebFlux event loop is a bug |
| Migrations | Flyway | Runs over JDBC at startup; that's fine, it's not on the request path |
| Cache / coordination | Redis 7+ (M8 only) | Presence and routing table only — **never operations** |
| Build | Gradle (Kotlin DSL), multi-module | |
| Testing | JUnit 5, **jqwik**, AssertJ, Testcontainers | |
| Metrics | Micrometer + Spring Boot Actuator | |

### Frontend

| Concern | Choice | Notes |
|---|---|---|
| Framework | React 18 + TypeScript, Vite | |
| Editor | Plain `<textarea>` in v1 | CodeMirror only after M6, and only if time allows |
| State | Plain hooks + a hand-written store | No Redux/Zustand — the OT state machine is the state |
| WebSocket | Native `WebSocket` API | No socket.io |
| Styling | Anything minimal | Not the point |

### Explicitly rejected

Do not introduce these. If you think one is needed, raise it, don't add it.

- **STOMP / `spring-messaging`** — abstracts away the ordering and ack semantics being learned
- **Kafka** — correct at scale, but adds a hop without removing the affinity requirement
- **Yjs, ShareDB, Automerge, any OT/CRDT library** — that's the project
- **Lombok** — Java 21 records cover the need; keep the code readable without a plugin
- **JPA/Hibernate** — blocking, and the schema is too simple to need it
- **Kubernetes** — one container per instance is enough

---

## 3. Repository layout

```
.
├── CLAUDE.md
├── docs/
│   └── DESIGN.md                  ← authoritative spec
├── ot-core/                       ← PROTECTED. Pure Java. Zero Spring, zero I/O.
│   ├── src/main/java/.../ot/
│   └── src/test/java/.../ot/      ← PROTECTED (assertions)
├── server/
│   ├── src/main/java/.../
│   │   ├── config/                ← free
│   │   ├── ws/                    ← free (handler, codec, registry)
│   │   ├── session/               ← PROTECTED (sequencer, control loop)
│   │   ├── persistence/           ← free (entities, repositories)
│   │   └── api/                   ← free (REST for doc CRUD)
│   └── src/main/resources/db/migration/
├── client/
│   ├── src/ot/                    ← PROTECTED (state machine)
│   ├── src/components/            ← free
│   └── src/ws/                    ← free (transport)
├── docker-compose.yml
└── .github/workflows/
```

**`ot-core` must not depend on `server`.** Dependency direction is `server → ot-core`, never the
reverse. If `ot-core` ever needs a Spring import, something has gone wrong — say so.

---

## 4. Conventions

- **Operations are immutable records.** A transform returns new objects. Never mutate a logged op.
- **Sealed interfaces over inheritance.** `sealed interface Operation permits Insert, Delete, NoOp`
  with exhaustive `switch`. The compiler is the safety net.
- **No `null` for optional state.** Use an explicit `NoOp` — never `Optional<Operation>`, never `null`.
- **Fail loudly.** Out-of-bounds operations throw with the op and the state length in the message.
  Never clamp. Clamping turns a loud bug into permanent silent divergence.
- **No blocking calls on an event loop.** If it blocks, it goes on a bounded elastic scheduler.
- **Revision numbers are `long`,** gapless per document, assigned only by the sequencer.
- Conventional commits. One milestone per branch.

---

## 5. Design patterns

**Status legend:** `[ ]` not yet explained — you may not apply it. `[x]` explained and understood.

Update the checkbox only when Abhi says so.

### Core to the design

| Pattern | Where | Status | Why it's here |
|---|---|---|---|
| **State** | `client/src/ot/` — Synchronized / AwaitingAck / AwaitingWithBuffer | `[x]` | Behaviour of every event depends entirely on current state. A `switch` on an enum works but obscures the invariants; separate state objects make "one op in flight" structurally enforceable. **This is the textbook use case.** |
| **Command** | `Operation` + the revision log | `[x]` | Operations are reified requests with enough data to replay. The log is a command log. |
| **Event Sourcing** | `operations` table + `document_snapshots` | `[x]` | Document state is a fold over an append-only log. Snapshots are the optimisation, not the truth. |
| **Memento** | `document_snapshots` | `[x]` | Point-in-time state capture that can restore without replaying from zero. |
| **Actor model** | `DocumentSession`, one single-thread scheduler per doc | `[x]` | Not a GoF pattern — a concurrency model. Serialises commands rather than guarding fields. This is *why* it beats a lock here. |
| **Observer / Pub-Sub** | `Sinks.Many` fan-out per document | `[x]` | Session members subscribe; the session publishes. Backpressure strategy is a real decision. |
| **Registry** | `DocumentSessionRegistry` | `[x]` | `docId → live session`, with lifecycle: create on first join, evict on last leave + idle timeout. Eviction is the interesting part. |
| **Repository** | `persistence/` | `[x]` | Spring Data gives it for free. Listed for completeness. |

### Considered and rejected — know why

| Pattern | Why not |
|---|---|
| **Visitor** | The classic way to dispatch over op types. Java 21 sealed interfaces plus exhaustive pattern-matching `switch` give the same compile-time exhaustiveness with none of the double-dispatch ceremony. **Raise this in interviews** — it shows you know the pattern *and* when the language made it obsolete. |
| **Strategy** | Only one transform algorithm exists. Adding the indirection now is speculative generality. |
| **Singleton** | Session state must be per-document and evictable. A singleton registry bean is Spring's job, not a pattern you implement. |
| **Factory** | Records with static factory methods where useful. No factory classes. |

### How to handle a `[ ]` pattern

When a task first requires one:

1. Stop before writing code.
2. Explain the pattern generically — problem it solves, participants, the shape.
3. Explain how it maps to *this* codebase specifically, naming the actual classes.
4. Name one credible alternative and why it loses here.
5. Wait for Abhi.

---

## 6. Build and run

```bash
# Infrastructure
docker compose up -d postgres            # add redis at M8

# The OT core alone — the loop Abhi lives in during M2
./gradlew :ot-core:test

# Full build
./gradlew build

# Server (dev)
./gradlew :server:bootRun --args='--spring.profiles.active=dev'

# Client (dev)
cd client && npm run dev

# Property tests at full strength — slow, run before merging a milestone
./gradlew :ot-core:test -Djqwik.tries.default=1000000
```

---

## 7. Deployment path

Build it in this order. Do not skip ahead — each stage should work before the next is attempted.

### Stage 1 — Local (M1–M4)
`docker compose` with Postgres only. Server via `bootRun`, client via Vite dev server with a proxy.
No containerised app yet.

### Stage 2 — Containerised (M5)
Multi-stage `Dockerfile`: Gradle build stage → Eclipse Temurin JRE runtime stage. Use Spring Boot
**layered jars** so dependency layers cache between builds. Non-root user. `docker compose up`
brings up the whole stack.

### Stage 3 — CI (M5)
GitHub Actions on push: `./gradlew build`, run property tests, Testcontainers integration tests,
build the image. Fail the build on any jqwik property failure — no exceptions.

### Stage 4 — Single-instance hosted, free tier (M7)

Target stack — verify each provider's current limits before committing, these change often:

| Layer | Provider | Limits as of writing |
|---|---|---|
| Backend | Render free web service | 512 MB RAM, 0.1 vCPU, 750 instance-hours/month |
| Database | **Neon free** | 0.5 GB storage per project, no expiry, ~500 ms wake from scale-to-zero |
| Frontend | Cloudflare Pages or Netlify | Static build of `client/` |

**Do not use Render's free Postgres.** It expires 30 days after creation and is then deleted.
The op log is the source of truth and cannot live on a clock.

> **Critical:** deploy **one instance** until M8 exists. Two instances without affinity routing
> means two independent sequencers for the same document and permanent divergence. Set
> `min_instances = max_instances = 1`. Any autoscaling config is a correctness bug at this stage.

**JVM settings for a 512 MB container** — set as `JAVA_TOOL_OPTIONS`:

```
-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1
```

SerialGC beats G1 on a single core; G1's background threads cost more than they return at this
size. `TieredStopAtLevel=1` skips C2 compilation and cuts startup at the expense of peak
throughput, which is irrelevant here.

**Cold starts.** Render free services spin down after 15 minutes idle and take 30–60 seconds to
wake, plus JVM boot. Mitigations, in priority order:

1. **A demo GIF in the README** — two windows typing simultaneously. Most visitors never click the
   live link. This is the highest-value deployment task in the whole project.
2. A GitHub Actions cron hitting `/actuator/health` every 10 minutes. 750 hours covers a ~730-hour
   month, so 24/7 fits the quota. Unsupported by Render; may stop working.
3. A banner in the UI stating the cold-start delay.

**Optional stretch: GraalVM native image.** Spring Boot AOT supports WebFlux and R2DBC. Sub-second
startup, roughly a third the memory, and it eliminates the cold-start problem outright. Fiddly —
attempt only after M7 is stable. Good README line if it works.

### Stage 5 — Multi-instance (M8, optional)
Redis for the routing table. Consistent hash on `documentId`. Sticky routing at the load balancer
or an internal redirect to the owning instance. Only attempt this after M7 is solid.

**This cannot be demonstrated on a free tier** — it needs two containers plus Redis plus sticky
routing, and Render's single free web service makes it structurally impossible. Run it locally
with `docker compose`, record a 60-second capture of two instances converging on one document,
and link the video from the README.

### Observability (from M5)
Actuator health and readiness probes. Micrometer timers on: transform duration, ops per second per
document, revision-gap size at arrival, WebSocket fan-out latency. **Revision-gap distribution is
the interesting metric** — it directly measures how much real concurrency the system sees.
Structured JSON logging with `documentId` and `revision` on every op log line.

---

## 8. Milestone board

Update as work completes.

| ID | Deliverable | Owner | Status |
|---|---|---|---|
| M0 | DESIGN.md §5.3 transform table filled by hand | Abhi | done |
| M1 | Gradle multi-module skeleton, WebSocket echo | Claude | done |
| M2 | `ot-core`: apply, transform, compose; TP1 property green at 10⁶ | **Abhi** | done (Path B) |
| M3 | Server control loop, revision log, session registry | **Abhi** | done (Path B) |
| M4 | Client three-state machine | **Abhi** | done (Path B) |
| M5 | Postgres persistence, snapshots, Docker, CI | Split | done (Path B) |
| M6 | Cursors and presence | **Abhi** | |
| M7 | Reconnection with op replay | **Abhi** | |
| M8 | Multi-instance affinity routing | Optional | |

**Current focus: M6.** M2–M5 are done (Path B). M4 added an `OperationSequence` transform to
`ot-core` and switched the wire/envelope to carry a sequence per op (Option A), so the client
buffer can hold non-mergeable edits. M5 made `DocumentSession` a mailbox + `concatMap` actor that
persists each operation (synchronous append, §7.4) before broadcasting, rebuilds state from
snapshot + log on first command, and snapshots every N with two-snapshot retention; it ships a
multi-stage layered Dockerfile and GitHub Actions CI. Reload-after-restart verified both by a
Testcontainers test and live over the wire.

---

## 9. Quick reference — refuse these

- Writing anything in a protected path
- Showing protected-path code "as an example" or "for reference"
- Weakening, skipping, or narrowing a failing property test
- Adding a rejected dependency from §2
- Applying a `[ ]` pattern from §5 before explaining it
- Deploying more than one instance before M8
- Suggesting a CRDT library, or STOMP, or Kafka
- Writing test assertions
- Jumping to M3+ while M2's property is red
