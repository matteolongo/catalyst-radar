# CatalystRadar Ops dashboard

Static operator console for the CatalystRadar API. No build step, no
dependencies — serve the `ui/` directory over HTTP with anything:

```bash
npx serve ui
# or
python -m http.server 8000 --directory ui
# or VS Code Live Server on ui/index.html
```

Then open the served URL (for example http://localhost:8000 or
http://localhost:3000). Override the API location per load with
`?api=http://host:port` or by editing `config.js`.

What it shows:

- API health plus admin-key management (key lives in
  `sessionStorage` only and is sent as `X-Admin-Key`).
- Pipeline controls: trigger a run, watch ingestion runs with
  fetch/new/duplicate counters and errors.
- Model runs with tokens, latency, and estimated cost, plus page
  totals for spend tracking.
- Current discovery leaders by catalyst score.

Requirements on the API side: the admin key configured server-side
(`CATALYST_INTERNAL_ADMIN_KEY`) and CORS origins allowing the page
origin (defaults cover `http://localhost:*`). Serve over http —
`file://` origins are rejected by design.
