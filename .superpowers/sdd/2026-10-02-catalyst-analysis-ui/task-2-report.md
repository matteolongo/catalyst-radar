
# Task 2 implementation report

## Scope and result

Implemented additive, read-only explanation fields for the current company catalyst and event APIs. The score-v1 calculation, score version, persisted snapshot score, existing response fields, and database schema remain unchanged.

- A single Kotlin state-band table now drives both `stateForScore` and the catalyst `stateBand` response. Bands are NORMAL 0–25, WATCH 25–45, BUILDING 45–65, CATALYZED 65–80, and HIGH 80–100. `maxInclusive` marks the HIGH upper bound.
- `ScoreCalculator` returns each included signed event contribution with the exact factors it used. It also returns the pre-convergence sum, family count, convergence multiplier, converged raw score, normalization scale, and cutoff. The catalyst API maps these under `scoreCalculation`; top drivers expose their factor object while retaining the existing `contribution` value and top-three absolute-contribution ordering.
- Top drivers now carry family, event timestamp, discovery timestamp, cluster ID, original `quoteOrFact` evidence, and source metadata. Event feeds return the same source DTO plus ticker and company name. Existing top-level event `sourceDocumentId` remains.
- A focused batched read joins events to companies and source documents, selecting only source ID, title, provider, publication timestamp, and canonical URL. No source body or raw payload is selected or serialized. Missing source and optional publication/URL values remain nullable.
- The latest snapshot explanation selects events whose database `created_at` is no later than that snapshot's database `created_at`. It uses snapshot `asOf` only as the score decay clock. Event `discoveredAt` can predate extraction and persistence, so it does not establish availability.
- The catalyst API returns `explanationStatus`: `MATCHED`, `SCORE_MISMATCH`, or `VERSION_MISMATCH`. Score and taxonomy version checks run before the score comparison. On either mismatch, `topDrivers` is empty and `scoreCalculation` is null, so a recomputation cannot be presented as an explanation of the persisted snapshot.

## TDD evidence

1. Added state-boundary and contribution-factor tests. The first focused Gradle run failed at test compilation because `stateBandForScore` and the new factor/aggregate fields were absent. Added the minimal scoring and band implementation; the focused score/view tests passed.
2. Added store and view tests for company/source metadata, evidence, and aggregate details. The next focused run failed at test compilation because the new read-model fields were absent. Added the focused metadata read and view mapping; those tests passed.
3. Added public controller JSON assertions for state bands, aggregate factors, driver evidence/source, ticker/name, optional metadata, and exclusion of body/raw payload. These four tests failed with missing JSON paths before the DTO mapping, then passed after it.
4. Added a point-in-time regression test for an event discovered before the snapshot `asOf` but persisted after the snapshot. The initial follow-up test run failed at compilation because the new consistency signal was absent. After implementation, temporarily restoring the unrestricted event read made this exact regression test fail with the late event counted; restoring the availability read made it pass.
5. Added tests that alter a persisted snapshot's score or score version and verify status plus suppression of drivers and aggregate calculation. Controller JSON tests cover both mismatch statuses. The scoring writer and score-v1 calculator were not changed in this follow-up.

## Verification

- Focused Gradle run covering `ScoreCalculatorTest`, `CatalystViewServiceTest`, `CatalystControllerTest`, `CompanyEventsControllerTest`, `EventsControllerTest`, and `EventStoreTest`: passed.
- Full `gradlew.bat test --console=plain`: passed, 279 tests, zero failures.
- `git diff --check`: no whitespace errors. Git emitted only Windows LF/CRLF conversion notices.

## Contracts and limitations

`stateBand` has `state`, `minScore`, `maxScore`, and `maxInclusive`. `scoreCalculation` has `contributionSum`, `familyCount`, `convergenceMultiplier`, `rawScore`, `normalizationScale`, and `contributionCutoff`. Each driver retains `contribution` and adds `family`, `eventTimestamp`, `discoveredAt`, `clusterId`, `factors`, `evidence`, and `source`. The event response adds `ticker`, `companyName`, and the same `source` shape. `source` contains `sourceDocumentId`, `title`, `provider`, `publishedAt`, and `canonicalUrl`. `explanationStatus` reports whether current score-v1 recomputation agrees with the persisted snapshot's score and versions.

The response explains the latest snapshot using current score-v1 logic and event rows created by the snapshot's persistence time. `MATCHED` confirms score and version agreement; historical event membership was not stored, so it cannot prove the original canonical event set when different sets yield the same score. Database `created_at` is the best available proxy for persistence order; concurrent transactions can share or overlap timestamps. Historical snapshots do not store driver breakdowns, so this change does not claim to explain earlier timeline points. Consumers must distinguish persisted normalized company score from raw event contribution and validate `canonicalUrl` before rendering it as a link.
