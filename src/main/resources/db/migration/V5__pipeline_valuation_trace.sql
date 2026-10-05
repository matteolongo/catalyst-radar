ALTER TABLE operation_runs ADD COLUMN trace_version VARCHAR(64);
ALTER TABLE source_documents ADD COLUMN trace_version VARCHAR(64);
ALTER TABLE document_processing_attempts ADD CONSTRAINT uq_attempt_document_run UNIQUE (id, source_document_id, operation_run_id);

CREATE TABLE document_processing_steps (
    id UUID PRIMARY KEY,
    operation_run_id UUID NOT NULL REFERENCES operation_runs(id),
    source_document_id UUID NOT NULL REFERENCES source_documents(id),
    processing_attempt_id UUID,
    stage VARCHAR(32) NOT NULL CHECK (stage IN ('SOURCE_NORMALIZATION','SOURCE_REGISTRATION','COMPANY_RESOLUTION','EVENT_EXTRACTION','EVENT_VALIDATION','EVENT_NORMALIZATION','EVENT_CLUSTERING','EVENT_PERSISTENCE')),
    sequence INTEGER NOT NULL CHECK (sequence BETWEEN 1 AND 8),
    CHECK (sequence = CASE stage WHEN 'SOURCE_NORMALIZATION' THEN 1 WHEN 'SOURCE_REGISTRATION' THEN 2
        WHEN 'COMPANY_RESOLUTION' THEN 3 WHEN 'EVENT_EXTRACTION' THEN 4 WHEN 'EVENT_VALIDATION' THEN 5
        WHEN 'EVENT_NORMALIZATION' THEN 6 WHEN 'EVENT_CLUSTERING' THEN 7 WHEN 'EVENT_PERSISTENCE' THEN 8 END),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','SKIPPED','FAILED','INTERRUPTED')),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    input_count INTEGER CHECK (input_count >= 0),
    output_count INTEGER CHECK (output_count >= 0),
    events_inserted INTEGER CHECK (events_inserted >= 0),
    events_reused INTEGER CHECK (events_reused >= 0),
    error_code VARCHAR(64),
    error_message VARCHAR(256),
    FOREIGN KEY (processing_attempt_id, source_document_id, operation_run_id)
        REFERENCES document_processing_attempts(id, source_document_id, operation_run_id),
    CHECK ((sequence <= 3 AND processing_attempt_id IS NULL) OR (sequence >= 4 AND processing_attempt_id IS NOT NULL)),
    CHECK ((status IN ('RUNNING','INTERRUPTED') AND finished_at IS NULL) OR (status IN ('SUCCEEDED','SKIPPED','FAILED') AND finished_at IS NOT NULL))
);
CREATE INDEX ix_document_steps_feed ON document_processing_steps(source_document_id, started_at DESC, id DESC);
CREATE INDEX ix_document_steps_attempt ON document_processing_steps(processing_attempt_id, started_at DESC, id DESC);
CREATE INDEX ix_document_steps_run ON document_processing_steps(operation_run_id, status);

CREATE TABLE company_valuation_records (
    id UUID PRIMARY KEY,
    operation_run_id UUID NOT NULL REFERENCES operation_runs(id),
    company_id UUID NOT NULL REFERENCES companies(id),
    snapshot_id UUID NOT NULL UNIQUE REFERENCES catalyst_snapshots(id),
    as_of TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    score_version VARCHAR(64) NOT NULL,
    taxonomy_version VARCHAR(64) NOT NULL,
    previous_snapshot_id UUID REFERENCES catalyst_snapshots(id),
    before_score DOUBLE PRECISION,
    before_state VARCHAR(16),
    after_score DOUBLE PRECISION NOT NULL,
    after_state VARCHAR(16) NOT NULL,
    velocity_1d DOUBLE PRECISION NOT NULL,
    velocity_3d DOUBLE PRECISION NOT NULL,
    velocity_7d DOUBLE PRECISION NOT NULL,
    transition_id UUID REFERENCES state_transitions(id),
    contribution_sum DOUBLE PRECISION NOT NULL,
    family_count INTEGER NOT NULL CHECK (family_count >= 0),
    convergence_multiplier DOUBLE PRECISION NOT NULL,
    raw_score DOUBLE PRECISION NOT NULL,
    normalization_scale DOUBLE PRECISION NOT NULL,
    contribution_cutoff DOUBLE PRECISION NOT NULL,
    UNIQUE(operation_run_id, company_id),
    CHECK ((previous_snapshot_id IS NULL AND before_score IS NULL AND before_state IS NULL) OR
        (previous_snapshot_id IS NOT NULL AND before_score IS NOT NULL AND before_state IS NOT NULL))
);
CREATE INDEX ix_valuation_run_feed ON company_valuation_records(operation_run_id, as_of DESC, id DESC);
CREATE INDEX ix_valuation_company_feed ON company_valuation_records(company_id, as_of DESC, id DESC);

CREATE TABLE company_valuation_event_contributions (
    id UUID PRIMARY KEY,
    valuation_id UUID NOT NULL REFERENCES company_valuation_records(id),
    event_id UUID NOT NULL REFERENCES events(id),
    cluster_id UUID REFERENCES event_clusters(id),
    created_at TIMESTAMPTZ NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    sign DOUBLE PRECISION NOT NULL,
    base_weight DOUBLE PRECISION NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    materiality_factor DOUBLE PRECISION NOT NULL,
    surprise_factor DOUBLE PRECISION NOT NULL,
    source_quality_factor DOUBLE PRECISION NOT NULL,
    directness_factor DOUBLE PRECISION NOT NULL,
    time_decay_factor DOUBLE PRECISION NOT NULL,
    supporting_sources JSONB NOT NULL DEFAULT '[]',
    supporting_documents_total BIGINT NOT NULL CHECK (supporting_documents_total >= 0),
    UNIQUE(valuation_id, event_id)
);
CREATE INDEX ix_valuation_contribution_feed ON company_valuation_event_contributions(valuation_id, created_at DESC, id DESC);

-- Complete immutable lineage; display metadata above is deliberately bounded.
CREATE TABLE company_valuation_contribution_sources (
    contribution_id UUID NOT NULL REFERENCES company_valuation_event_contributions(id),
    source_document_id UUID NOT NULL REFERENCES source_documents(id),
    event_id UUID NOT NULL REFERENCES events(id),
    PRIMARY KEY(contribution_id, source_document_id)
);
CREATE INDEX ix_valuation_source_document ON company_valuation_contribution_sources(source_document_id, contribution_id);
