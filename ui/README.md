# CatalystRadar analysis dashboard

The dashboard is packaged with the Spring Boot application. Follow the
[local startup steps](../README.md#operator-dashboard-poc), then open
`http://localhost:8080/ops/index.html`. It uses the same origin as the API and
has no separate build step.

## Views

- **Discover** opens first. Filter companies by state, minimum score,
  minimum 7-day velocity, and **exact-match** sector text. Results are bounded
  pages and open company analysis by ticker.
- **Company analysis** shows the saved current score and state, the server's
  state range, current metrics, a reconstructed explanation when its score and
  versions match the saved snapshot, stored history, and contextual events.
  Raw event contributions are not normalized score points. The chart contains
  stored snapshot points only; a selected range returns at most 200 snapshots
  and 200 transitions. Contextual events do not establish causes of historical
  scores or states.
- **Events** searches across companies using ticker, family, type, direction,
  and first-captured dates. The date controls filter `discoveredAt`, including
  the full selected through date in UTC. Event date (`eventTimestamp`), source
  publication date (`source.publishedAt`), and first captured (`discoveredAt`)
  are displayed separately. Each bounded page is loaded with **Load more**;
  reports retain their evidence and cluster identifiers. A ticker opens its
  company analysis.
- **Operations** keeps the pipeline trigger, ingestion and model run tables,
  token counts, latency, and page cost totals. Model run details show version
  and source document identifiers, without prompts or document bodies.

## Keys and access

Run history and pipeline controls require an admin key. The `local` profile
uses `local-dev-secret`; other profiles need `CATALYST_INTERNAL_ADMIN_KEY`.
If public API authentication is enabled, issue a public API key through
`POST /internal/api-clients` and enter it in the API key field for Discover,
Company, and Events. The admin key goes only to `/internal/*`; the public API
key goes only to `/v1/*`. Keys live in this tab's `sessionStorage`. **Clear
keys** removes both and clears protected run data.

**Run pipeline now** can make live provider and LLM calls when their keys are
configured and may incur costs. Use HTTPS and restrict access for remote
deployments. Do not deploy the `local` profile remotely.
