# CatalystRadar Ops dashboard

The dashboard is packaged with the Spring Boot application. Follow the
[local startup steps](../README.md#operator-dashboard-poc), then open
`http://localhost:8080/ops/index.html`. The `local` profile uses
`local-dev-secret` as its admin key. Outside that profile, configure
`CATALYST_INTERNAL_ADMIN_KEY`. No separate web server, UI build step, or CORS
configuration is needed; the page always calls the API that served it.

What it shows:

- API health plus admin and public API key management. Keys live in the
  browser tab's `sessionStorage`. The admin key is sent only to `/internal/*`;
  the API key is sent only to `/v1/*` when public authentication is enabled.
- Pipeline controls: trigger a run, watch ingestion runs with
  fetch/new/duplicate counters and errors.
- Model runs with tokens, latency, and estimated cost, plus page
  totals for spend tracking.
- Current discovery leaders by catalyst score.

Run history and pipeline controls require the server-side admin key
(`CATALYST_INTERNAL_ADMIN_KEY`). Clear entered keys with **Clear keys** or
by closing the tab.

Use HTTPS and restrict access to the dashboard for remote deployments. Never
deploy the `local` profile remotely. This key-entry flow is intended for an
operator POC, not an Internet-facing login system.

If `catalyst.api.auth-enabled=true`, create a public API key with
`POST /internal/api-clients` and save it in the API key field to load leaders.
