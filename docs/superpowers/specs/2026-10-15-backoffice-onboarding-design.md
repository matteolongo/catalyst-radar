# Backoffice onboarding design

## Goal

Help a first-time CatalystRadar user understand the existing backoffice without
starting work, changing configuration, or storing onboarding data on the
server.

## Entry and persistence

The first visit offers three non-blocking choices: manage the pipeline, explore
catalysts, or continue later. A versioned browser-local record stores the
chosen path, completed explanatory steps, and whether the first manual pipeline
run has been acknowledged. It contains no credentials, configuration values, or
server data. Settings can restart the guide.

## Operator path

1. Overview explains that `Pending` means a captured document is waiting for
   processing; simply reading the guide does not start a pipeline.
2. Pipeline describes acquisition, extraction, deduplication, and scoring. The
   first deliberate run checks the existing safe configuration endpoint and
   shows only configured extraction and embedding models, with their roles.
   The user must explicitly continue before the existing POST is sent.
3. Documents explains `Pending`, `Processing`, `Completed`, `Skipped`, and
   recorded errors, plus inspection of a document trace.
4. Models & costs explains recorded model status and latency/activity, without
   adding cost coaching.
5. The guide returns the user to Overview.

## Analyst path

1. Discover introduces state, score, and velocity filters.
2. Company explains score, state, velocity, and recorded drivers; it is never a
   buy/sell recommendation.
3. Events explains evidence and event clusters, including duplicate reports.
4. The guide returns the user to Discover.

## Error and access behavior

The guide is contextual and never blocks navigation. If the model configuration
cannot be read or contains no configured models, it keeps the pipeline stopped,
explains the problem, and directs the user to Settings. Failed reads do not
erase progress. Existing access and API error handling remain authoritative.

## Constraints

- Use the existing vanilla JavaScript backoffice and its existing safe
  `/internal/operations/config` response; add no API, backend persistence,
  dependencies, analytics, account model, or synchronization.
- Keep model names dynamic and display only configured models.
- Do not auto-run providers or models; the existing manual pipeline POST remains
  the only execution path.
- Keep the guide keyboard accessible and non-modal.
