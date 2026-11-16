# ADR 0002: Companies outside the seeded universe become candidates, then tracked companies

## Status

Accepted

## Context

The active universe is the version-controlled S&P 500 + Nasdaq-100 seed.
Ingestion asks providers for news about active tickers only, and
`SourceDocumentRegistrationService` stores an article that names no
configured company as `Unresolved`: kept for audit, never extracted. The
provider tickers of every article are kept only as a joined string in
`source_documents.raw_payload`.

So the system already sees other companies, but nothing can surface them,
and a company that keeps appearing in the news can never be scored.

Expanding the universe has costs that scale with it:

- every active ticker adds provider requests to each ingestion cycle;
- every queued document adds an extraction call and embeddings;
- a company chosen *because* it later appeared in the news would bias any
  historical replay that starts before it was chosen.

## Decision

1. **Candidate ledger.** Registration records each provider ticker that
   does not resolve to a configured company in
   `source_document_unresolved_tickers`, for every stored document, in the
   same transaction. Existing documents are backfilled from `raw_payload`.
   Candidates are read through `GET /internal/universe/candidates`. Counts
   are documents, not events.
2. **Manual promotion first.** `POST /internal/universe/promotions` adds a
   ticker after the company-reference provider confirms it is a US equity.
   Automatic promotion is a separate, disabled-by-default rule built on the
   same service.
3. **Hard cap.** `catalyst.universe.max-active-companies` bounds the active
   universe. Promotion is refused at the cap.
4. **Backfill without re-extraction.** Promotion links the candidate's
   stored documents and queues only those that were never queued
   (stored as `Unresolved`). Documents already processed for another company
   are linked but not re-extracted: the extraction cache is keyed by
   document, extractor version and model, and re-extracting would risk
   different events for the company that already used them.
5. **Point-in-time.** Backfilled events keep the source document's
   `discovered_at`, as every event does. A promoted company records
   `tracked_since`; replay refuses a cutoff before it, because choosing the
   company used information from after that cutoff.
6. **Market-wide scan is opt-in.** `catalyst.ingestion.scan-mode=market`
   asks the primary provider for all news in the window instead of news per
   ticker. Registration is unchanged, so LLM cost still scales with
   resolved documents only. ~~The default stays `tickers`.~~ Amended: the
   default is `market` (see below).

## Consequences

- No new infrastructure. One table, two columns on `companies`, two
  internal endpoints and one ingestion mode.
- In `tickers` mode most candidate documents also name a tracked company,
  so promotion backfills little; the candidate's coverage grows from the
  next cycle. Market mode produces documents that only name the candidate.
- Provider quota in market mode depends on market-wide news volume per
  window; the existing page cap and `WINDOW_TRUNCATED` handling bound it.
- Seeded companies have `tracked_since = NULL` and keep today's replay
  behaviour.

## Amendment: `market` is the default scan mode

Polygon's `ticker` filter takes a single symbol; the comma-separated list
ingestion sent matched nothing, so per-ticker scanning had never fetched a
Polygon article. One request per ticker does not fit the free plan's 5
requests per minute: 517 tickers take over 100 minutes, against a 30-minute
interval. A market scan needs one to a few requests per cycle, so it is the
default. `tickers` remains for paid plans, now one ticker per request, and
every provider request is paced by `catalyst.rate-limits`.
