# CatalystRadar operations backoffice

The optional backoffice is packaged at `/ops/index.html` in the Spring Boot
application. Follow the [local startup steps](../README.md#operator-dashboard-poc).
It uses the API's origin, HTML/CSS and vanilla JavaScript, with no separate
frontend build. See the [operator/API reference](../docs/operations-backoffice.md).

## Six areas

- **Overview** opens first: ingestion freshness, current queue, recorded activity,
  estimated model cost, inspection signals and observed dependencies.
- **Pipeline** shows pipeline and daily-snapshot cycles, provider ingestion runs,
  issues and explicit associations. **Run pipeline now** uses the existing POST.
- **Documents** searches sources and opens Overview, Attempts, Model calls,
  Events and Source tabs. Source text loads only when requested.
- **Models & costs** shows full server period totals, coverage and bounded calls.
  Detail shows safe errors and recorded source/attempt/run IDs.
- **Intelligence** contains Discover/Events tabs and Company analysis. Original
  Discover/Events/Company URLs still work. Events has an inclusive UTC through
  date; Documents uses next-day exclusive `to`.
- **Settings** stores access keys and shows safe read-only configuration,
  versions, provider-configured booleans and scheduler settings.

The activity picker (24h/7d) applies to Overview, Pipeline and Models, independently
of the current queue and Documents. All times display UTC. Refresh updates the
current area; auto-refresh runs every 30 seconds while visible. Selection,
filters and source text survive refresh for the same source/credential revision.
Back/Forward restores local investigation context.

## Keys and execution

Administrative reads require `X-Admin-Key`. The local profile uses
`local-dev-secret`; other profiles use `CATALYST_INTERNAL_ADMIN_KEY`. With public
API authentication enabled, Intelligence requires a separately issued public
API key. Admin keys go only to `/internal/*`; public keys only to `/v1/*`.
Settings keeps them in this tab's `sessionStorage`. Changing/clearing keys
clears protected data and invalidates obsolete reads. Source inspection needs
admin access even with a public key; Settings can return to the selected source.

**Run pipeline now** is the only UI execution control, using
`POST /internal/ingestion/runs`. Its caption warns that configured provider/LLM
calls may incur costs. Pending/observed active pipelines disable another trigger;
a busy response creates no extra history row. There is no document retry,
scheduler editing, cancel control or automatic replay. GET reads never trigger
paid work. Use HTTPS and restricted remote access; do not expose the local profile.

## Investigation walkthrough

1. Follow an **Overview** queue/failure signal into filtered Documents.
2. Select a source; distinguish captured, published and created timestamps.
   Open **Source** for stored text, capped at 20,000 characters.
3. Inspect a failed **Model call** in Models & costs. Read its safe error and
   usage/cost coverage, including known usage retained on malformed paid output.
4. Follow the recorded attempt association back to **Attempts**. Compare actual
   attempt outcomes and counters; an attempt count is not historical evidence.
5. Follow its operation ID into **Pipeline**, inspecting issues/provider runs.
   Document completion and company scoring success are separate outcomes.
6. Follow a ticker into **Company** in Intelligence. Back returns to the opener;
   source inspection appears only when a real source ID exists.

Missing IDs mean missing recorded associations. The UI never guesses links from
timestamps or claims an unrecorded call never occurred. Legacy origins/attempts
and real interrupted finish times can be absent.

Company labels its snapshot **Saved as of ... UTC**. Its explanation is a current
reconstruction when score/versions match the saved snapshot; raw contributions
are not normalized score points. History contains stored points only: at most
200 snapshots and 200 transitions per range. Contextual events do not establish
original historical scoring drivers or causes. Active artifacts are `taxonomy-v1`,
`event-extractor-v1` (prompt/extractor), and `score-v1`; Settings reports configured
values. Server bands are NORMAL 0–25, WATCH 25–45, BUILDING 45–65, CATALYZED 65–80,
HIGH 80–100. A threshold enters the higher band.
