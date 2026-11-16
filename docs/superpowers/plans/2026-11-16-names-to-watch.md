# Names To Watch — Implementation Plan

> Source: flow review of 2026-11-15. The code was read, not run. Items marked **VERIFY FIRST**
> rest on behaviour that was read or recalled, not observed; confirm them before building on them.

**Goal:** Make CatalystRadar answer "which companies should I be following, and what changed?"
without the operator having to understand the pipeline.

**Read before starting:** `AGENTS.md`, `docs/architecture.md`, `docs/implementation-v0.1.md`.

## Implementation status (2026-11-16)

All eight items are implemented as one commit each on `claude/intelligent-gauss-5y9ddj`
rather than eight stacked branches. Deviations from the plan below:

- **PR 3:** daily snapshots stay off in Compose. The snapshot scheduler runs at startup and
  saves a snapshot for every active company, so enabling it on a fresh install fills Radar with
  ~600 zero scores before any news exists and hides the setup checklist. The checklist and the
  README tell the operator to enable it after the first pipeline run.
- **PR 4:** the following-only flag lives in the Radar URL as `following=1`; the ticker list is
  expanded from browser storage when the request is built, so URLs stay short.
- **PR 5:** candidates use bounded offset paging (`offset` ≤ 10,000), not a cursor: the ranking is
  by an aggregate count, which has no stable keyset. Unresolved tickers are recorded for every
  stored document, including documents that also name a tracked company, because in `tickers`
  scan mode almost every article names one.
- **PR 6:** the lookup is a separate port, `CompanyLookupProvider`, implemented by the Polygon
  adapter only, on the existing `/v3/reference/tickers` endpoint with `ticker=` and `type=CS`.
  Documents already processed for another company are linked but not re-extracted (the
  extraction cache is keyed by document, not by company set). Provider failures map to 502
  `PROVIDER_UNAVAILABLE` for this endpoint only. Replay refuses cutoffs before `tracked_since`.
- **PR 8:** promotions are counted in a metric and visible through `companies.origin` /
  `tracked_since`, not recorded as pipeline issues, because they are not failures.

Still unverified against live providers: that Polygon returns market-wide news when the
`ticker` filter is omitted, and the exact behaviour of `/v3/reference/tickers?ticker=` for
unknown symbols. Both are covered by WireMock tests only.

## Gaps this plan closes

| # | Gap | Today |
|---|-----|-------|
| A | Negative build-ups are invisible in the ranking | `downside_score` is stored per snapshot (V6) and shown in company history, but `DiscoveryStore` can neither sort nor filter by it |
| B | No "what changed" view | `state_transitions` is only readable per company via `/v1/companies/{ticker}/timeline` |
| C | First run is not self-evident | Radar shows "No company scores have been saved yet." with no next step; daily snapshots are off, so rows go Stale |
| D | No personal shortlist | Nothing marks companies the user chose to follow |
| E | Only seeded companies can ever appear | Ingestion queries active tickers only; articles naming other tickers are stored as `Unresolved` and never processed |

## Out of scope

- Email, push or webhook delivery. Webhooks are excluded by AGENTS.md §43; the "what changed"
  view is a pull read. Push delivery needs its own ADR.
- Buy/sell wording, price data, technical indicators.
- Any change to `score-v1`, `taxonomy-v1` or `event-extractor-v1`. If a PR would alter a
  persisted score, stop and report.
- Server-side user accounts or per-user storage.

## Ground rules for every PR

- AGENTS.md §36 for fixes; new behaviour gets tests first.
- Public `/v1` changes are additive only.
- Never edit an applied Flyway migration. The next free version is `V9`.
- No new dependencies or infrastructure.
- No live provider calls in tests: WireMock and fixtures only.
- Update `README.md`, `ui/README.md` and `docs/operations-backoffice.md` when visible behaviour changes.
- Checks before pushing:
  - `node --test ui/app.test.js ui/operations.test.js ui/operations-model.test.js ui/leaderboard.test.js ui/notebooks.test.js`
  - `./gradlew clean test --console=plain` (needs Docker for Testcontainers)

## Stack layout

PRs 1–4 are independent of each other and can merge in any order. PRs 5–8 are a stack and
start only after the ADR in PR 5 is accepted.

| # | Branch | Gap | Size | Area |
|---|--------|-----|------|------|
| 1 | `feat/watch-01-downside-ranking` | A | S | Discovery API, Radar |
| 2 | `feat/watch-02-changes-feed` | B | M | New public read, Radar |
| 3 | `feat/watch-03-first-run` | C | S | Radar empty state, compose defaults, docs |
| 4 | `feat/watch-04-following` | D | S | UI only |
| 5 | `feat/watch-05-ticker-candidates` | E | M | ADR, migration, internal read |
| 6 | `feat/watch-06-candidate-promotion` | E | L | Reference lookup, promotion, backfill |
| 7 | `feat/watch-07-market-scan` | E | M | Ingestion mode |
| 8 | `feat/watch-08-auto-promotion` | E | S | Deterministic rule, optional |

---

## PR 1 — Rank and filter by downside

**Files:** `persistence/discovery/DiscoveryStore.kt`, `api/publicapi/DiscoveryController.kt`,
`api/dto/DiscoveryResponses.kt`, `ui/leaderboard.js`, `ui/index.html`, `ui/app.js`.

1. Add `DiscoverySort.DOWNSIDE` (`s.downside_score DESC`) and `minDownside` (0..100) to `DiscoveryQuery`.
2. Add `downsideScore` to `DiscoveryRow` and `DiscoveryEntryResponse`.
3. Radar: add "Highest downside" to **Rank by**, show a Downside column, add a Minimum downside filter.
4. Snapshots written before V6 hold `downside_score = 0`. Label the column so a 0 on an old
   snapshot is not read as "no negative news": reuse the existing Stale marker, no new logic.

**Tests:** `DiscoveryStoreTest` (sort order, filter, ticker tie-break), `DiscoveryControllerTest`
(range validation), `leaderboard.test.js` (query building, rendering).

**Done when:** a company whose headline score is 0 because negatives dominate appears at the top
of the downside ranking.

## PR 2 — "What changed" feed

**New read:** `GET /v1/discovery/transitions?since=&direction=&state=&limit=&cursor=`

- Rows from `state_transitions` joined to active `companies`, ordered `transitioned_at DESC, id DESC`.
- `since` defaults to 24 hours before now and is capped at 30 days. `limit` 1..100, default 50.
- `direction` is `UPGRADE` or `DOWNGRADE`, derived in Kotlin from the `CatalystState` order.
  It is not stored and no LLM is involved.
- Keyset cursor on `(transitioned_at, id)`; the list grows without bound, so no offset paging.
- Response row: ticker, name, sector, fromState, toState, direction, score, scoreVersion, transitionedAt.

**Migration `V9__add_state_transition_time_index.sql`:** index on
`state_transitions (transitioned_at DESC, id DESC)`.

**UI:** a **Changes** section at the top of Radar with a 24h / 7d picker: "Moved up" and
"Moved down" lists, each row linking to the company record. "New since your last visit" uses a
timestamp in `localStorage`; nothing is stored on the server.

**Limit to state in the UI text:** a transition is a band crossing. A large move inside one band
does not appear here; the 7-day change ranking covers that.

**Tests:** store test (ordering, window, cursor stability, inactive companies excluded),
controller test (validation, bounds), `OpenApiDocsTest`, UI tests for rendering and empty window.

## PR 3 — First run that explains itself

1. **Radar empty state.** Replace the single sentence with a checklist driven by reads that
   already exist (the safe configuration view and the operations summary):
   admin key saved → providers and models configured → pipeline has run → daily snapshots enabled.
   Each unmet step links to the screen that fixes it. Without an admin key only step 1 is shown;
   the UI never guesses the rest.
2. **Daily snapshots on in Compose.** `DailySnapshotService` calls no provider or model, so set
   `CATALYST_SNAPSHOTS_ENABLED` to default `true` in `compose.yaml` only. `application.yml` stays
   `false`. **VERIFY FIRST** that `FixtureSchedulerIsolationTest` still holds.
3. **Docs.** `README.md` says the UI opens on Overview; `ui/index.html` opens on Radar. Fix the
   README. Also move "UI" out of the v0.1 out-of-scope list or mark the backoffice as the
   explicit exception, so the two documents stop contradicting each other.

**Not doing:** prefilling or embedding the admin key in the UI, merging the admin and public keys.

## PR 4 — Following (UI only)

- A star on Radar rows and on the company record; tickers kept in `localStorage`.
- A "Following only" toggle on Radar and on the Changes section. Because discovery has no
  ticker filter, add an optional repeated `ticker` parameter (max 50) to
  `/v1/discovery/catalyzed` and `/v1/discovery/transitions` rather than filtering loaded pages.
- State plainly in the UI that the list lives in this browser.

---

## PRs 5–8 — Finding companies outside the seeded universe

### Decision required first: ADR `docs/adr/0002-universe-expansion.md`

This changes the ingestion strategy and the cost profile, so AGENTS.md §39 asks for an ADR.
It must settle:

| Question | Recommendation |
|----------|----------------|
| Promote automatically or by hand? | By hand first (PR 6). Automatic only after the candidate list has been observed on real data (PR 8). |
| Hard cap on active companies? | Yes, configured. Every active ticker costs provider calls and LLM calls each cycle. |
| What `discovered_at` do backfilled events get? | The instant the system produced them, never the article date. Snapshots are not backdated; replay stays cutoff-safe. |
| Scope check for a candidate | Must resolve to a US equity through the reference provider; otherwise it stays a candidate. |

### PR 5 — Candidate ledger

Unresolved tickers already reach the system: a per-ticker fetch returns articles that also name
other symbols, and those are stored. They are only kept as a joined string in `raw_payload`.

- **Migration `V10__add_unresolved_document_tickers.sql`:** table
  `source_document_unresolved_tickers (source_document_id, ticker, created_at)`, primary key on
  both IDs, index on `ticker`. Backfill from `raw_payload` for documents with no company link.
- `SourceDocumentRegistrationService` writes one row per provider ticker that did not resolve,
  in the existing transaction.
- `GET /internal/universe/candidates?since=&minDocuments=&limit=&cursor=`: ticker, distinct
  document count, first and last `discovered_at`. Counts are documents, not events
  (AGENTS.md §11); say so in the response field names.
- Operations UI: a Candidates table under Operations. Read only.

**Tests:** registration test for mixed resolved/unresolved tickers, migration backfill test,
store test for counts and window.

### PR 6 — Manual promotion and backfill

- Port: add `suspend fun findUsEquity(ticker: String): CompanyReference?` to
  `CompanyReferenceProvider`; implement in the Polygon adapter, WireMock tested.
  **VERIFY FIRST** which Polygon endpoint returns a single ticker's reference data and its
  error shape for unknown symbols.
- **Migration `V11__add_company_origin.sql`:** `companies.origin` (`SEED` | `PROMOTED`), default `SEED`.
- `POST /internal/universe/promotions` with `{ "ticker": "..." }`:
  1. reference lookup outside any transaction;
  2. reject if not a US equity or if the active-company cap is reached;
  3. in one transaction: insert the company, link its stored unresolved documents, queue them
     for extraction, delete their candidate rows.
- Idempotent: promoting an existing ticker returns the existing company and queues nothing twice.
- Queued documents flow through the normal pipeline; no special scoring path.

**Tests:** promotion happy path end to end with fixtures (document → event → snapshot →
discovery row), idempotency, cap reached, non-equity rejected, point-in-time test proving a
replay with a cutoff before promotion excludes the backfilled events.

### PR 7 — Market-wide scan

- New ingestion mode in `IngestionProperties`: `scan-mode: tickers | market` (default `tickers`).
  In `market` mode `IngestionService` issues one windowed, paginated request with no ticker
  filter instead of chunking the universe. `PolygonNewsProvider` already omits the `ticker`
  parameter when the list is empty (`PolygonNewsProvider.kt:63`).
  **VERIFY FIRST** that Polygon returns market-wide news for that request and how many pages a
  2-hour window produces; the existing `max-pages-per-chunk` bound and `WINDOW_TRUNCATED`
  handling apply.
- Finnhub fetches per ticker and cannot serve this mode; in `market` mode the fallback keeps
  using the active-ticker list.
- Registration is unchanged: known tickers queue, unknown ones land in the candidate ledger.
  LLM cost does not rise with scan volume, because only resolved documents are extracted.

**Tests:** `IngestionServiceTest` for the single-request path, truncation, fallback behaviour;
idempotency test across both modes for the same article.

### PR 8 — Automatic promotion (optional)

- Deterministic rule in named configuration: at least N distinct documents within D days, and
  below the active-company cap, with a per-cycle promotion limit.
- Runs after ingestion, outside the fetch loop, through the same service as PR 6.
- Disabled by default. Records each promotion as an operation issue or run counter so it is
  visible in Pipeline.

---

## Order and stopping points

1. PR 1, PR 2, PR 3 give the "names to watch and what changed" outcome inside the current
   universe. This is the smallest useful release.
2. PR 4 adds the personal shortlist.
3. PRs 5–6 let the user add a company that keeps appearing. Stop here unless the candidate
   list shows the seeded universe is the real limit.
4. PRs 7–8 only if that evidence exists; they carry the provider-quota risk.

## Open decisions

| Decision | Default taken in this plan |
|----------|----------------------------|
| Is push delivery (email/webhook) wanted? | No; pull view only |
| Following list stored server-side? | No; browser only |
| Promotion automatic from day one? | No; manual, automatic behind a flag later |
| Daily snapshots on by default? | In Compose only |
