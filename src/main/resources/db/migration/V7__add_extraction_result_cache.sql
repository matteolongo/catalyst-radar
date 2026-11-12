-- Validated-input extraction results keyed by what determines them: the
-- immutable source document, the extractor/prompt version and the model.
-- Replay and retries reuse these instead of calling the model again, so a
-- replay is reproducible and free. Only successful, parsed results are kept.
CREATE TABLE extraction_results (
    source_document_id UUID NOT NULL REFERENCES source_documents (id),
    extractor_version VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_document_id, extractor_version, model)
);
