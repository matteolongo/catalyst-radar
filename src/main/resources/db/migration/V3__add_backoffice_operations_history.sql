CREATE TABLE operation_runs (
  id UUID PRIMARY KEY,
  kind VARCHAR(24) NOT NULL CHECK (kind IN ('PIPELINE', 'DAILY_SNAPSHOTS')),
  trigger_type VARCHAR(16) NOT NULL CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
  status VARCHAR(16) NOT NULL CHECK (status IN
    ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
  phase VARCHAR(16) NOT NULL CHECK (phase IN ('INGESTION', 'PROCESSING', 'SCORING', 'FINISHED')),
  as_of TIMESTAMPTZ NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL,
  ingestion_finished_at TIMESTAMPTZ,
  processing_finished_at TIMESTAMPTZ,
  capture_complete BOOLEAN NOT NULL DEFAULT FALSE,
  documents_considered INTEGER NOT NULL DEFAULT 0 CHECK (documents_considered >= 0),
  documents_completed INTEGER NOT NULL DEFAULT 0 CHECK (documents_completed >= 0),
  documents_skipped INTEGER NOT NULL DEFAULT 0 CHECK (documents_skipped >= 0),
  documents_retry_scheduled INTEGER NOT NULL DEFAULT 0 CHECK (documents_retry_scheduled >= 0),
  documents_terminal_failures INTEGER NOT NULL DEFAULT 0 CHECK (documents_terminal_failures >= 0),
  events_inserted INTEGER NOT NULL DEFAULT 0 CHECK (events_inserted >= 0),
  events_reused INTEGER NOT NULL DEFAULT 0 CHECK (events_reused >= 0),
  companies_considered INTEGER NOT NULL DEFAULT 0 CHECK (companies_considered >= 0),
  companies_rescored INTEGER NOT NULL DEFAULT 0 CHECK (companies_rescored >= 0),
  companies_failed INTEGER NOT NULL DEFAULT 0 CHECK (companies_failed >= 0),
  error_code VARCHAR(64),
  error_message VARCHAR(500),
  CHECK (finished_at IS NULL OR finished_at >= started_at)
);
CREATE INDEX ix_operation_runs_started_id ON operation_runs (started_at DESC, id DESC);
CREATE INDEX ix_operation_runs_kind_started ON operation_runs (kind, started_at DESC, id DESC);

CREATE TABLE document_processing_attempts (
  id UUID PRIMARY KEY,
  source_document_id UUID NOT NULL REFERENCES source_documents(id),
  operation_run_id UUID NOT NULL REFERENCES operation_runs(id),
  attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
  status VARCHAR(24) NOT NULL CHECK (status IN
    ('RUNNING', 'COMPLETED', 'SKIPPED', 'RETRYABLE_ERROR', 'TERMINAL_ERROR', 'INTERRUPTED')),
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL,
  next_attempt_at TIMESTAMPTZ,
  events_inserted INTEGER NOT NULL DEFAULT 0 CHECK (events_inserted >= 0),
  events_reused INTEGER NOT NULL DEFAULT 0 CHECK (events_reused >= 0),
  error_code VARCHAR(64),
  error_message VARCHAR(500),
  UNIQUE (source_document_id, attempt_number),
  UNIQUE (id, source_document_id),
  CHECK (finished_at IS NULL OR finished_at >= started_at)
);
CREATE INDEX ix_processing_attempts_document_started
  ON document_processing_attempts (source_document_id, started_at DESC, id DESC);
CREATE INDEX ix_processing_attempts_run_started
  ON document_processing_attempts (operation_run_id, started_at DESC, id DESC);

CREATE TABLE operation_run_issues (
  id UUID PRIMARY KEY,
  operation_run_id UUID NOT NULL REFERENCES operation_runs(id),
  phase VARCHAR(16) NOT NULL CHECK (phase IN ('INGESTION', 'PROCESSING', 'SCORING')),
  source_document_id UUID REFERENCES source_documents(id),
  company_id UUID REFERENCES companies(id),
  error_code VARCHAR(64) NOT NULL,
  error_message VARCHAR(500) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_operation_issues_run_created
  ON operation_run_issues (operation_run_id, created_at DESC, id DESC);

ALTER TABLE ingestion_runs ADD COLUMN operation_run_id UUID REFERENCES operation_runs(id);
ALTER TABLE ingestion_runs ADD COLUMN error_code VARCHAR(64);
ALTER TABLE source_documents ADD COLUMN first_ingestion_run_id UUID REFERENCES ingestion_runs(id);
ALTER TABLE model_runs ADD COLUMN processing_attempt_id UUID;
ALTER TABLE model_runs ADD COLUMN error_code VARCHAR(64);
ALTER TABLE model_runs ADD CONSTRAINT fk_model_run_attempt_document
  FOREIGN KEY (processing_attempt_id, source_document_id)
  REFERENCES document_processing_attempts (id, source_document_id);
ALTER TABLE model_runs ADD CONSTRAINT ck_model_run_attempt_requires_document
  CHECK (processing_attempt_id IS NULL OR source_document_id IS NOT NULL);

CREATE INDEX ix_documents_discovered_id ON source_documents (discovered_at DESC, id DESC);
CREATE INDEX ix_documents_provider_discovered ON source_documents (provider, discovered_at DESC, id DESC);
CREATE INDEX ix_documents_first_ingestion ON source_documents (first_ingestion_run_id)
  WHERE first_ingestion_run_id IS NOT NULL;
CREATE INDEX ix_events_source_document_discovered ON events (source_document_id, discovered_at DESC, id DESC);
CREATE INDEX ix_model_runs_created_id ON model_runs (created_at DESC, id DESC);
CREATE INDEX ix_model_runs_document_created ON model_runs (source_document_id, created_at DESC, id DESC);
CREATE INDEX ix_model_runs_attempt_created ON model_runs (processing_attempt_id, created_at, id)
  WHERE processing_attempt_id IS NOT NULL;
CREATE INDEX ix_ingestion_runs_started_id ON ingestion_runs (started_at DESC, id DESC);
CREATE INDEX ix_ingestion_runs_operation ON ingestion_runs (operation_run_id)
  WHERE operation_run_id IS NOT NULL;

-- Prior adapters estimated zero when usage was absent. Absence is not free usage.
UPDATE model_runs SET estimated_cost = NULL
WHERE input_tokens IS NULL OR (operation = 'extract' AND output_tokens IS NULL)
   OR (provider = 'openai' AND model NOT IN ('gpt-4o-mini', 'text-embedding-3-small')
       AND estimated_cost = 0);
