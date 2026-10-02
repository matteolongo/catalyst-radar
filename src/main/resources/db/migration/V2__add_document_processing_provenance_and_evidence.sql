-- Durable source-document processing state and evidence provenance.
-- These structures are additive so V1 databases remain readable.

CREATE TABLE source_document_companies (
    source_document_id UUID NOT NULL REFERENCES source_documents (id) ON DELETE CASCADE,
    company_id UUID NOT NULL REFERENCES companies (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_document_id, company_id)
);
CREATE INDEX ix_source_document_companies_company_document
    ON source_document_companies (company_id, source_document_id);

CREATE TABLE document_processing (
    source_document_id UUID PRIMARY KEY REFERENCES source_documents (id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    last_error_message TEXT,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_document_processing_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'SKIPPED', 'RETRYABLE_ERROR', 'TERMINAL_ERROR')
    ),
    CONSTRAINT ck_document_processing_attempt_count CHECK (attempt_count >= 0)
);
CREATE INDEX ix_document_processing_due
    ON document_processing (status, next_attempt_at);

ALTER TABLE events ADD COLUMN evidence JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE events ADD COLUMN event_fingerprint CHAR(64);
CREATE UNIQUE INDEX uq_events_source_document_fingerprint
    ON events (source_document_id, event_fingerprint)
    WHERE source_document_id IS NOT NULL AND event_fingerprint IS NOT NULL;

INSERT INTO source_document_companies (source_document_id, company_id)
SELECT DISTINCT source_document_id, company_id
FROM events
WHERE source_document_id IS NOT NULL
ON CONFLICT DO NOTHING;
