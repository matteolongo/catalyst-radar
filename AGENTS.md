# AGENTS.md

## Purpose

This file defines the engineering rules for AI agents and developers working on **CatalystRadar**.

CatalystRadar is a standalone market-intelligence API that ingests company-related information, extracts structured catalyst events, maintains historical catalyst state for US equities, and exposes this intelligence through a versioned REST API.

Before making architectural or domain-level changes, read:

* `docs/architecture.md`
* `docs/implementation-v0.1.md`

Those documents are the source of truth for v0.1.

If this file conflicts with an implementation detail in those documents, prefer the more specific design document unless the task explicitly requires changing the architecture.

---

# 1. Core engineering principles

## 1.1 Keep the system simple

CatalystRadar v0.1 is intentionally a modular monolith.

Do not introduce distributed infrastructure without a demonstrated requirement.

Do not add:

* Kafka
* RabbitMQ
* Redis
* Kubernetes
* Elasticsearch
* a dedicated vector database
* extra microservices
* extra LLM providers
* complex orchestration frameworks

unless explicitly requested.

PostgreSQL is the primary persistence layer.

`pgvector` is used for vector similarity when needed.

---

## 1.2 Domain code must not depend on providers

Third-party integrations must remain behind adapters.

The core domain must never expose types such as:

```text
PolygonArticle
FinnhubNews
OpenAiResponse
```

Provider-specific DTOs belong inside provider packages.

Convert them into CatalystRadar domain models at the boundary.

Correct:

```text
Polygon API
    ↓
PolygonNewsDto
    ↓
PolygonNewsProvider
    ↓
SourceDocument
```

Incorrect:

```text
PolygonNewsDto
    ↓
ScoringEngine
```

---

## 1.3 LLMs extract. Application code decides.

LLMs may:

* extract facts
* classify events
* identify companies
* estimate structured attributes
* summarize evidence

LLMs must not determine:

* final catalyst score
* state transitions
* API filtering behavior
* deterministic business rules

The rule is:

> LLMs interpret unstructured information. Kotlin implements deterministic decisions.

---

## 1.4 Preserve point-in-time correctness

Historical analysis must never use information that was unavailable at the timestamp being evaluated.

Always distinguish:

```text
eventTimestamp
publishedAt
discoveredAt
createdAt
```

Do not silently substitute one for another.

Historical replay and evaluation must use only documents that were available before the replay cutoff.

Avoid look-ahead bias at all costs.

---

## 1.5 Everything important must be replayable

Raw source documents are valuable inputs.

The system must make it possible to regenerate:

* extracted events
* event clusters
* catalyst scores
* state transitions
* historical snapshots

when:

* extraction logic changes
* prompts change
* taxonomy changes
* scoring changes

Do not design flows that destroy the original source information.

---

# 2. Technology baseline

Use:

```text
Kotlin
JVM 21
Spring Boot
Gradle Kotlin DSL

PostgreSQL
pgvector
Flyway

JUnit
Testcontainers
WireMock

Micrometer
Spring Boot Actuator
```

External integrations for v0.1:

```text
Polygon
Finnhub
OpenAI
```

Do not replace these technologies unless the task explicitly asks for an architectural change.

---

# 3. Project architecture

Prefer package-by-feature/domain boundaries rather than generic technical-layer packages.

Recommended structure:

```text
com.catalystradar

├── company
├── event
├── ingestion
├── extraction
├── clustering
├── scoring
├── discovery
├── provider
├── api
├── security
├── persistence
└── common
```

Each feature may internally contain:

```text
domain
application
infrastructure
api
```

when useful.

Do not create abstractions merely to match a textbook architecture.

Add an abstraction only when it creates a meaningful boundary.

---

# 4. Dependency direction

Prefer:

```text
API
 ↓
Application
 ↓
Domain
 ↑
Infrastructure adapters
```

The domain should know as little as possible about:

* Spring
* HTTP
* PostgreSQL
* external APIs
* serialization
* OpenAI

Business logic should generally be testable without booting Spring.

---

# 5. Kotlin code style

## 5.1 General rules

Prefer idiomatic Kotlin.

Use:

* immutable values
* `data class`
* sealed types when appropriate
* nullable types deliberately
* extension functions sparingly
* expression-style functions when readable

Prefer:

```kotlin
val
```

over:

```kotlin
var
```

unless mutation is genuinely required.

Avoid Java-style Kotlin.

Bad:

```kotlin
val result = ArrayList<Event>()

for (item in items) {
    if (item.isValid()) {
        result.add(item)
    }
}
```

Prefer:

```kotlin
val result = items
    .filter { it.isValid() }
    .map { it.toEvent() }
```

when this remains readable.

---

## 5.2 Avoid clever code

Readability is more important than minimizing line count.

Do not create:

* deeply nested functional chains
* obscure operator overloads
* reflection-heavy solutions
* unnecessary DSLs
* magic extension functions

Prefer explicit domain language.

---

## 5.3 Naming

Use names that describe business concepts.

Good:

```kotlin
CatalystScore
EventCluster
SourceDocument
CatalystSnapshot
ScoreVelocity
```

Bad:

```kotlin
Processor
Manager
Handler2
Helper
Utils
DataObject
```

Avoid generic `Utils` classes.

Put behavior near the domain concept it belongs to.

---

## 5.4 Functions

Keep functions focused.

A function should normally do one conceptual thing.

Prefer:

```kotlin
calculateEventScore(event)
```

over:

```kotlin
processEverything(event)
```

Functions with many boolean parameters are a design smell.

Bad:

```kotlin
calculate(event, true, false, true)
```

Prefer typed configuration or explicit methods.

---

## 5.5 Nullability

Do not use `!!` except in narrowly controlled test code or truly impossible states.

Prefer:

```kotlin
requireNotNull(...)
```

when absence represents an invariant violation.

Otherwise model absence explicitly.

---

## 5.6 Enums and sealed types

Use enums for stable closed classifications such as:

```kotlin
enum class Direction {
    POSITIVE,
    NEGATIVE,
    NEUTRAL,
    MIXED
}
```

Use sealed hierarchies when variants require different payloads or behavior.

---

# 6. Domain modeling

The following concepts must remain distinct:

```text
SourceDocument
Event
EventCluster
CatalystSnapshot
CatalystState
```

A source document is not an event.

An event is not an article.

Multiple documents may describe one event.

One document may describe multiple events.

Do not collapse these concepts for implementation convenience.

---

# 7. Event taxonomy

Use the controlled taxonomy defined in the architecture documentation.

Top-level families include:

```text
EARNINGS
GUIDANCE
COMMERCIAL
PRODUCT_TECH
CORPORATE_ACTION
CAPITAL_ALLOCATION
ANALYST
REGULATORY
LEGAL
MANAGEMENT_OWNERSHIP
INDUSTRY_EXTERNAL
```

Do not introduce new event types casually.

When a new event type is required:

1. verify that an existing type cannot represent it;
2. document why it matters for forward expectations;
3. define its direction semantics;
4. define its scoring behavior;
5. add extraction tests;
6. update the taxonomy documentation.

---

# 8. Scoring engine

Scoring must be deterministic.

Conceptually:

```text
EventScore =
    BaseWeight
  × Confidence
  × SourceQuality
  × Materiality
  × Surprise
  × Directness
  × Novelty
  × TimeDecay
```

Do not embed scoring rules inside prompts.

Do not hardcode scoring rules throughout the codebase.

Centralize score configuration.

Every persisted score must include a version identifier such as:

```text
score-v1
```

Changes that modify historical scoring behavior require a new scoring version.

---

# 9. State transitions

Current states:

```text
NORMAL
WATCH
BUILDING
CATALYZED
HIGH
```

State transitions must be derived from deterministic score rules.

Do not allow API controllers, providers, or LLM responses to directly change catalyst state.

The scoring/state application service owns this behavior.

---

# 10. Time decay

Event relevance decreases over time.

Decay rules must remain configurable and testable.

Do not scatter constants such as:

```kotlin
0.93
0.82
14
30
```

through domain code.

Use named configuration.

Example:

```yaml
catalyst:
  scoring:
    guidance-raise:
      base-weight: 10
      half-life-days: 20
```

Exact configuration format may evolve, but scoring constants must remain centralized.

---

# 11. Event deduplication

Multiple articles about the same event must not multiply the catalyst score.

Deduplication may consider:

```text
company
event type
time window
semantic similarity
source relationships
```

A canonical event cluster represents the underlying real-world event.

Do not use article count as event count.

Syndicated copies must not create additional catalyst impact.

---

# 12. Provider design

Providers must implement stable interfaces.

Examples:

```kotlin
interface NewsProvider {
    suspend fun fetch(query: NewsQuery): List<RawArticle>
}
```

```kotlin
interface MarketDataProvider {
    suspend fun getCompany(ticker: String): CompanyReference?
}
```

```kotlin
interface EventExtractionProvider {
    suspend fun extract(
        document: SourceDocument
    ): ExtractedEvents
}
```

Provider-specific errors must be translated into application-level errors where appropriate.

Do not leak external response schemas beyond adapters.

---

# 13. Polygon and Finnhub

For v0.1:

* Polygon is the primary provider where supported.
* Finnhub acts as a secondary or fallback provider where useful.

Do not duplicate ingestion blindly across providers.

When multiple providers return the same source document or event, deduplicate it.

Provider priority must remain configurable.

---

# 14. OpenAI integration

All LLM calls must:

* use structured output where possible;
* validate returned data;
* record model information;
* record prompt/extractor version;
* capture token usage;
* capture latency;
* capture failures;
* be replayable from stored source documents.

Never trust model output without validation.

Treat model output as untrusted external input.

---

# 15. Prompt management

Prompts are versioned artifacts.

Do not build large prompts inline in application services.

Keep prompt templates in a dedicated location.

Example:

```text
src/main/resources/prompts/
    event-extractor-v1.txt
```

Every model run must persist:

```text
model
promptVersion
extractorVersion
inputTokens
outputTokens
latencyMs
estimatedCost
```

Changing extraction behavior requires updating the prompt/extractor version.

---

# 16. Database rules

PostgreSQL is the source of truth.

Use Flyway for every schema change.

Never modify an existing applied migration.

Bad:

```text
edit V1__init.sql after production use
```

Correct:

```text
V2__add_event_materiality.sql
```

Migration names should clearly describe the change.

---

# 17. Database naming

Use:

```text
snake_case
```

for database identifiers.

Examples:

```text
source_documents
event_clusters
catalyst_snapshots
created_at
event_timestamp
```

Prefer UUID identifiers for domain entities where already defined by the schema.

Use explicit foreign keys.

Add indexes based on real access patterns.

---

# 18. Transactions

Keep transaction boundaries explicit.

Typical ingestion behavior:

```text
fetch
    ↓
normalize
    ↓
persist source document
    ↓
extract events
    ↓
persist events
    ↓
cluster
    ↓
recalculate affected company
```

Do not wrap remote API calls inside long-running database transactions.

Perform external network calls before or outside narrow persistence transactions where practical.

---

# 19. Idempotency

Scheduled ingestion must be safe to run repeatedly.

A document already processed must not produce duplicate records or duplicate scoring impact.

Prefer stable external identifiers when available.

Otherwise use deterministic hashes from canonical fields.

Every retryable operation should be designed with idempotency in mind.

---

# 20. API design

All public endpoints must be versioned.

Use:

```text
/v1/...
```

Do not expose internal persistence models directly.

Create API response DTOs.

Public API changes must consider backward compatibility.

---

# 21. REST conventions

Use standard HTTP semantics.

Examples:

```text
GET    read resource
POST   create or trigger operation
PUT    replace resource
PATCH  partial update
DELETE remove resource
```

Do not use:

```text
GET /runIngestion
```

Prefer:

```text
POST /internal/ingestion/runs
```

where applicable.

---

# 22. Error responses

Expose stable machine-readable errors.

Prefer a consistent structure such as:

```json
{
  "code": "COMPANY_NOT_FOUND",
  "message": "Company with ticker XYZ was not found",
  "requestId": "..."
}
```

Do not return stack traces or provider error payloads to API clients.

---

# 23. Validation

Validate incoming API parameters at the boundary.

Examples:

* ticker format
* pagination bounds
* valid states
* score ranges
* limits

Domain invariants must also be enforced inside domain/application code.

Controller validation alone is not sufficient.

---

# 24. API pagination

Collection endpoints must use bounded pagination.

Never expose unbounded endpoints returning the entire event history.

Provide a deterministic sort order.

Prefer cursor pagination when datasets become large; simple bounded pagination is acceptable in v0.1 where defined.

---

# 25. Security

Never commit:

* API keys
* tokens
* database passwords
* private credentials

Secrets come from environment variables or deployment secret management.

`.env` files must be ignored by Git.

Never log secrets.

Never include full authorization headers in logs.

---

# 26. API authentication

v0.1 uses API keys.

Store only API-key hashes.

Never store raw API keys after issuance.

Authentication logic belongs in the security boundary, not individual controllers.

---

# 27. Logging

Use structured logging.

Logs should include useful context such as:

```text
requestId
ticker
companyId
sourceDocumentId
eventId
provider
ingestionRunId
```

Do not log entire news articles by default.

Do not log complete LLM prompts/responses at normal production log levels.

Do not log secrets or authentication data.

---

# 28. Observability

Every important external dependency should expose metrics.

Examples:

```text
provider request count
provider error count
provider latency

LLM request count
LLM latency
token usage
estimated cost
extraction failures

documents ingested
events extracted
events deduplicated
companies rescored
state transitions
```

Use Micrometer-compatible metrics.

Health checks must distinguish application health from optional external-provider degradation where appropriate.

---

# 29. Coroutines and concurrency

Prefer Kotlin coroutines for asynchronous application flows.

Avoid blocking calls inside coroutine contexts intended for non-blocking execution.

Third-party blocking clients must run on appropriate dispatchers or use non-blocking clients.

Concurrency limits must be explicit when calling external APIs.

Do not create unlimited parallel LLM or provider requests.

Respect provider rate limits.

---

# 30. Retries

Retry only operations that are safe to retry.

Use bounded retries with backoff.

Good candidates:

* transient HTTP 5xx
* timeout
* rate-limit response with known retry semantics

Do not blindly retry:

* validation errors
* authentication errors
* malformed LLM responses indefinitely
* deterministic 4xx errors

Retries must preserve idempotency.

---

# 31. Testing philosophy

Tests should protect behavior, not implementation structure.

Prefer testing externally observable domain behavior.

---

# 32. Unit tests

Unit test:

* event scoring
* time decay
* state transitions
* convergence bonus
* velocity calculation
* normalization
* clustering rules
* company resolution
* taxonomy behavior

Domain tests should not require Spring.

---

# 33. Integration tests

Use Testcontainers for PostgreSQL integration tests.

Do not rely on a developer's local database.

Integration tests should verify:

* Flyway migrations
* repository behavior
* pgvector integration
* API persistence flows
* transactional behavior

---

# 34. External provider tests

Do not call live Polygon, Finnhub, or OpenAI APIs during normal CI.

Use:

* WireMock
* fixtures
* recorded representative responses where licensing permits

Live-provider smoke tests should be explicit and opt-in.

---

# 35. Golden extraction dataset

Maintain representative extraction cases.

Example:

```text
src/test/resources/golden/
```

Cases should include:

* earnings beat
* earnings miss
* guidance raise
* guidance cut
* contract win
* duplicate syndicated articles
* ambiguous company mentions
* multiple events in one article
* irrelevant financial news
* malformed/partial provider data

Prompt/model changes must be evaluated against these cases.

---

# 36. Regression protection

When fixing a bug:

1. reproduce it in a test;
2. make the test fail;
3. implement the fix;
4. confirm the test passes.

Avoid fixes that cannot be demonstrated.

---

# 37. Test naming

Test names should describe behavior.

Prefer:

```kotlin
fun `guidance raise increases catalyst score`()
```

over:

```kotlin
fun testScoring1()
```

---

# 38. Comments

Prefer self-explanatory code.

Comments should explain:

* why something exists;
* unusual domain behavior;
* non-obvious constraints;
* external-provider quirks.

Do not write comments that merely repeat the code.

Bad:

```kotlin
// increment count
count++
```

---

# 39. Documentation

When behavior visible to API consumers changes, update relevant documentation.

Important architectural decisions should be documented as ADRs when appropriate.

Recommended:

```text
docs/adr/
```

An ADR is appropriate when deciding things such as:

* replacing a provider;
* introducing Redis;
* adding Kafka;
* changing persistence strategy;
* changing scoring architecture;
* adding another service boundary.

---

# 40. Dependencies

Add dependencies conservatively.

Before adding a library, ask:

1. Can the standard library solve this?
2. Does Spring already provide it?
3. Is the library actively maintained?
4. Does it materially simplify the implementation?
5. Does it introduce unnecessary runtime complexity?

Avoid dependencies for trivial functionality.

---

# 41. Performance

Do not optimize without evidence.

However, avoid obvious inefficiencies:

* N+1 queries
* loading full event history unnecessarily
* repeated provider requests for identical data
* repeated embedding generation
* unbounded lists
* sequential calls that are safely parallelizable

Measure before introducing caches.

---

# 42. Caching

Do not introduce Redis merely for caching in v0.1.

Use application or database-level optimizations first.

Introduce distributed caching only when metrics demonstrate a need.

---

# 43. Feature scope

Do not implement features outside the requested version.

v0.1 explicitly excludes:

* UI
* order execution
* portfolio management
* buy/sell recommendations
* technical analysis
* social sentiment
* SEC/IR primary-source ingestion
* webhooks
* Kafka
* Redis
* Kubernetes

Do not "helpfully" add these while completing another task.

---
