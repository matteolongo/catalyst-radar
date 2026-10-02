# CatalystRadar Ops dashboard

Static operator console for the CatalystRadar API. No build step or
application dependencies. For local use, serve the `ui/` directory over HTTP:

```bash
npx serve ui
# or
python -m http.server 8000 --directory ui
# or VS Code Live Server on ui/index.html
```

Then open the served URL (for example http://localhost:8000 or
http://localhost:3000). Set the API location in `config.js`.

What it shows:

- API health plus admin and public API key management. Keys live in
  `sessionStorage` only. The admin key is sent only to `/internal/*`;
  the API key is sent only to `/v1/*` when public authentication is enabled.
- Pipeline controls: trigger a run, watch ingestion runs with
  fetch/new/duplicate counters and errors.
- Model runs with tokens, latency, and estimated cost, plus page
  totals for spend tracking.
- Current discovery leaders by catalyst score.

Run history and pipeline controls require the server-side admin key
(`CATALYST_INTERNAL_ADMIN_KEY`). The API must allow the page origin for
CORS (defaults cover `http://localhost:*`). `file://` origins are rejected.

Use HTTP only on your own machine. For a remote deployment, serve both
the dashboard and API over HTTPS and configure an exact allowed origin.

If `catalyst.api.auth-enabled=true`, create a public API key with
`POST /internal/api-clients` and save it in the API key field to load leaders.
