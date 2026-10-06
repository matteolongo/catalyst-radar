# CatalystRadar operations backoffice

The optional backoffice is packaged at `/ops/index.html` in the Spring Boot
application. Follow the [local startup steps](../README.md#operator-dashboard-poc).
It uses the API's origin, HTML/CSS and vanilla JavaScript, with no separate
frontend build. See the [operator/API reference](../docs/operations-backoffice.md).

## Six areas

- **Overview** opens first: ingestion freshness, current queue, recorded activity,
  estimated model cost, inspection signals and observed dependencies.
- **Pipeline** shows pipeline and daily-snapshot cycles, provider ingestion runs,
  issues, cycle documents, and frozen company valuations with explicit associations. **Run pipeline now** uses the existing POST.
- **Documents** opens a chronological Timeline by default, with Overview,
  Valuations, Attempts, Model calls, Events and Source tabs. Source text loads only when requested.
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


## Recorded pipeline and valuation investigation

The shared dark shell remains dark independently of the operating-system theme.
Document lists use full width until a detail is selected, then split on wide
screens and stack on narrow screens. Pipeline detail opens above the full-width
cycle table, keeping outcomes readable at desktop sizes. Its outcome summary
precedes expandable metadata and counters. Labels stay with filter controls.

1. Open **Pipeline**, select an actual pipeline or daily snapshot cycle, and
   inspect its recorded phases and counters. **Documents** and **Company
   valuations** are separate paginated collections for that exact cycle, across
   all dates. Company scoring failures stay in the cycle's **Issues**, without a
   fabricated valuation result.
2. Open a cycle document. **Timeline** lists only saved steps, ordered by actual
   start time and sequence within run/intake/attempt groups. Each stage shows its
   stored outcome, timestamps, available duration and counts, and safe failure
   category. **Load older steps** appends pages before arranging the loaded steps
   chronologically. Missing later stages are not implied successes; interrupted
   stages have no invented finish, duration or completion percentage.
3. Follow an attempt to its recorded all-date model calls, or open **Events** to
   inspect stored reports, cluster IDs and evidence. Attempt/run filters on the
   document model tab apply to loaded pages; load more for older calls. Opening
   **Source** is the explicit action that requests the protected text body.
4. Open the document's **Valuations** tab. These relationships come from the
   complete frozen source/event relation, not the ticker, cycle, current snapshot
   or nearby timestamps. The run filter remains attached to every page. Supporting
   documents beyond the display metadata cap still appear through this endpoint.
5. Open a valuation from the cycle, document, or **Company → Recorded valuations**.
   Company history defaults to all dates and groups loaded valuation rows by run.
   The protected subsection needs the admin key; public company analysis keeps
   its existing public access rules. A selected valuation retains explicit run,
   document and ticker context in the URL. Back/Forward restores that selection.
6. Read the saved before/after score and state, snapshot/transition IDs, as-of and
   creation timestamps, velocities, contribution sum, family count, convergence,
   raw score, normalization scale and cutoff. Expand each event contribution for
   recorded factors and frozen source evidence. Event, cluster, source, snapshot
   and transition IDs are displayed as stored; only existing destinations are
   linked. Supporting source metadata/facts explicitly indicate truncation.

For `score-v1`, the recorded calculation is `rawScore = contributionSum ×
convergenceMultiplier`. The score is zero when raw score is nonpositive;
otherwise it is `100 × rawScore / (rawScore + normalizationScale)`, bounded to
0–100. The UI presents the stored numbers and saved `afterScore`; it does not
recalculate them. Raw event contributions are not final score points.
Deduplication is structural rather than a separate stored novelty factor, and
multiple supporting reports do not multiply the canonical contribution.

A null `traceVersion` means **Detailed trace not recorded**. Run completion
(`captureComplete`) remains separate from audit coverage. A legacy document can
have newly recorded attempt steps while its intake remains unavailable. Prior
score/state, unknown counts and unfinished durations remain **Not recorded** or
**Unknown**, never a zero baseline. Current reconstructed explanations and
contextual company events do not supply historical driver membership. Captured
live calculations record actual inputs; they do not prove historical replay
eligibility for backdated scoring.

All new reads use bounded 25-row pages and retain their parent IDs and opaque
cursors. A deep document/valuation URL is shown only after that valuation is found
in the document's frozen valuation pages; if it is older, open **Valuations** and
load more to verify the relationship. No unbounded search is performed. Run and
company detail also check the valuation's stored parent IDs. Key changes clear
protected records and abort/invalidate stale reads. Existing pipeline execution
is the only write control; trace navigation never invokes a provider or model.

### History freshness and access denial

The default 30-second refresh and the main **Refresh** action continue reading
current cycle phases/counters and document overview/queue metadata. For trace
collections with one loaded page, a latest-page read updates matching rows and
retains previously loaded rows. An overlapping page keeps the existing next
cursor; if there is no overlap, the new page's cursor allows the missing interval
to be traversed manually. Every request remains bounded to 25 rows.

After **Load more** or retention grows beyond 25 rows, that collection pauses
automatic and main-button history refresh. Its notice shows the earliest/latest
page-read times from the server. Rows reflect their individual last read values:
running steps, attempt outcomes and global document state shown in retained cycle
rows may have changed. Current cycle phases and the document overview continue
refreshing independently. Load more uses the retained cursor; no automatic loop
fetches older pages. Paused document history is not repainted by metadata polls.

Selecting a valuation from another cycle of the same company preserves its loaded
company history, cursor and paused-refresh state. The selected valuation and its
contributions are cleared and checked again for the new cycle context. Changing
company, screen or credentials clears the company history.

**Reload latest trace/history** deliberately replaces only that collection with
its latest 25 rows and resumes its normal refresh behavior. The selected exact
valuation and its already verified parent relationship stay available in the same
context and credentials, even if their row is older than this restarted page.
Reloading contributions keeps the saved valuation summary. A newly selected
document valuation still requires membership in the loaded frozen document
valuation pages. Changing run/document/attempt/company context or credentials
clears the detail and its proof; document filter changes reset document pages.

A denied trace or exact-valuation read clears both trace and existing protected
Pipeline/Document panels, invalidates concurrent reads, and shows a visible denial
message with a Settings link. Every known admin denial purges all protected
operational caches and rendered data, including previously loaded Source bodies,
document/model/cycle history, Overview and configuration. Returning to an earlier
screen requires a new authorized read. Saved keys remain available for recovery;
public company analysis and its independent requests remain usable.
