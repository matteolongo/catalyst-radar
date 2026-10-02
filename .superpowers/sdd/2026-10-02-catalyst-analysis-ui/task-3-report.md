# Task 3 implementation report

## Result

The company route now requests company metadata, current catalyst, a bounded stored timeline, and the first bounded company event page. Each panel handles its own failure. The page shows company identity, current score and state range, velocities, event counts, versions, and as-of time.

The 30-day, 90-day, and maximum-returned history views use `from`, `to`, and `limit=200`. The chart plots only returned snapshots as discrete points, sorts them by `asOf`, marks returned transitions at their persisted `at` times, and has a data table with date, score, state, and as-of. It draws no value between sparse observations. Zero and one snapshot produce explicit messages rather than a fabricated line. The maximum-returned label states the 200-item cap. A separate history request sequence prevents a late response from an earlier range selection from replacing the current range.

The “Why this state?” panel displays reconstructed drivers, directional/directness summaries, and collapsed scoring factors only for `RECONSTRUCTED_SCORE_MATCH`. It states that snapshot rows did not retain the original driver list. `SCORE_MISMATCH` and `VERSION_MISMATCH` suppress those details and explain that attribution is unavailable. The current normalized company score and raw event contribution remain separately labeled.

The company event feed groups loaded records by `clusterId`, with unclustered records standing alone. It preserves evidence and safe source metadata for each loaded event and appends bounded cursor pages. Event, source-publication, and first-captured times are separately labeled. External source links are rendered only for parsed HTTP(S) URLs. API text is escaped; source bodies and raw provider payloads are never requested or rendered.

## TDD and verification

1. Added seven company-view behavior tests first. `node --test ui/app.test.js` failed with seven expected failures because the route made only the existing discovery and health requests, not the four company requests.
2. Implemented the company panels, history chart/table, reconstructed explanation, safe source links, and clustered event feed. The suite reached 23 passes and one event-load failure; fixing the load-button state brought it to 24 passes.
3. Added two more tests for retaining loaded events after a cursor-page failure and preserving returned factor precision. Both failed before the refinements. The final run passed all 26 tests.
4. `git diff --check` reported no whitespace errors. Git printed only LF/CRLF conversion notices on Windows.
5. Follow-up review added a deferred timeline-response regression and a multi-snapshot no-polyline assertion. Both failed against the original chart/range behavior. After the fix, `node --test ui/app.test.js` passed 27 tests, `node --check ui/app.js` passed, and `git diff --check` passed with Windows LF/CRLF notices only.

## Limits

Timeline results are capped by the API at 200 snapshots and 200 transitions; the UI does not claim full history. Event pages contain only loaded records, so a cluster may have other reports beyond the loaded pages. The page treats company events as context and does not attribute historical snapshots to them.
