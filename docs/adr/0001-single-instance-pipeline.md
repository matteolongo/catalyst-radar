# ADR 0001: The pipeline runs in a single instance

## Status

Accepted

## Context

`PipelineService` guards against overlapping cycles with an in-process
`AtomicBoolean`, and `DocumentProcessingStore.findDue` returns every `PENDING`
and `PROCESSING` document. `PROCESSING` rows are retried on purpose, to recover
work interrupted by a restart.

Two application instances would therefore both see the same due documents and
process them twice. Extraction results are cached (`extraction_results`) and
event persistence is idempotent through fingerprints, so the damage would be
duplicate model and embedding calls, not duplicate scoring impact.

## Decision

Run exactly one application instance with ingestion or snapshots enabled.
Other instances may serve the read API with both schedulers disabled.

## Consequences

- Horizontal scaling of the pipeline needs a claim step first: a conditional
  `UPDATE ... RETURNING` with a lease timestamp on `document_processing`, or
  `FOR UPDATE SKIP LOCKED`, with `PROCESSING` rows reclaimed only after the
  lease expires. That change would supersede this ADR.
- `catalyst.pipeline.concurrency` raises throughput inside the one instance.
