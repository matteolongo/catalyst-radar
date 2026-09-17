-- CatalystRadar v0.1 initial schema.
--
-- Conventions: snake_case identifiers, UUID primary keys, timestamptz in UTC,
-- JSONB for provider payloads and schemaless attributes, VECTOR(1536) for
-- text-embedding-3-small embeddings with model metadata. Unique constraints
-- are part of ingestion idempotency, not just integrity.
--
-- Tables without adapters yet (company_aliases, model_runs, ingestion_runs,
-- api_clients, score_versions) are created now so the migration equals the
-- approved v0.1 schema; their adapters arrive with CR-04/CR-08/CR-09/CR-12/CR-16.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE companies (
    id UUID PRIMARY KEY,
    ticker VARCHAR(16) NOT NULL,
    name TEXT NOT NULL,
    exchange VARCHAR(32),
    sector VARCHAR(128),
    industry VARCHAR(128),
    country CHAR(2),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_companies_ticker UNIQUE (ticker)
);

CREATE TABLE company_aliases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies (id),
    alias TEXT NOT NULL,
    alias_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_company_aliases UNIQUE (company_id, alias)
);
CREATE INDEX ix_company_aliases_alias ON company_aliases (alias);

CREATE TABLE source_documents (
    id UUID PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    provider_document_id TEXT,
    canonical_url TEXT,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    published_at TIMESTAMPTZ,
    discovered_at TIMESTAMPTZ NOT NULL,
    content_hash CHAR(64),
    embedding VECTOR(1536),
    embedding_model VARCHAR(128),
    embedding_version VARCHAR(64),
    raw_payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_source_documents_provider_doc UNIQUE (provider, provider_document_id),
    CONSTRAINT uq_source_documents_content_hash UNIQUE (content_hash)
);

CREATE TABLE event_clusters (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    event_type VARCHAR(64) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    embedding VECTOR(1536),
    embedding_model VARCHAR(128),
    embedding_version VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_event_clusters_company_type_seen
    ON event_clusters (company_id, event_type, first_seen_at);

CREATE TABLE events (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    cluster_id UUID REFERENCES event_clusters (id),
    source_document_id UUID REFERENCES source_documents (id),
    event_type VARCHAR(64) NOT NULL,
    family VARCHAR(32) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    magnitude DOUBLE PRECISION,
    surprise DOUBLE PRECISION,
    materiality DOUBLE PRECISION,
    source_quality VARCHAR(32) NOT NULL,
    expected_horizon VARCHAR(32) NOT NULL,
    directness VARCHAR(16) NOT NULL,
    scheduled BOOLEAN NOT NULL DEFAULT FALSE,
    event_timestamp TIMESTAMPTZ,
    discovered_at TIMESTAMPTZ NOT NULL,
    taxonomy_version VARCHAR(64) NOT NULL,
    extractor_version VARCHAR(64) NOT NULL,
    attributes JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_events_company ON events (company_id);
CREATE INDEX ix_events_cluster ON events (cluster_id);

CREATE TABLE catalyst_snapshots (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    score DOUBLE PRECISION NOT NULL,
    score_version VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    velocity_1d DOUBLE PRECISION NOT NULL,
    velocity_3d DOUBLE PRECISION NOT NULL,
    velocity_7d DOUBLE PRECISION NOT NULL,
    taxonomy_version VARCHAR(64) NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_snapshots_company_as_of
    ON catalyst_snapshots (company_id, as_of DESC);

CREATE TABLE state_transitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies (id),
    from_state VARCHAR(16) NOT NULL,
    to_state VARCHAR(16) NOT NULL,
    score DOUBLE PRECISION NOT NULL,
    score_version VARCHAR(64) NOT NULL,
    transitioned_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE model_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(64) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64),
    extractor_version VARCHAR(64),
    source_document_id UUID REFERENCES source_documents (id),
    input_tokens INTEGER,
    output_tokens INTEGER,
    latency_ms BIGINT,
    estimated_cost NUMERIC(12, 6),
    success BOOLEAN NOT NULL,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ingestion_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    cursor TEXT,
    documents_fetched INTEGER NOT NULL DEFAULT 0,
    documents_new INTEGER NOT NULL DEFAULT 0,
    documents_duplicate INTEGER NOT NULL DEFAULT 0,
    error_summary TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

CREATE TABLE api_clients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    key_prefix VARCHAR(32) NOT NULL,
    key_hash TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    CONSTRAINT uq_api_clients_prefix UNIQUE (key_prefix)
);

CREATE TABLE score_versions (
    version VARCHAR(64) PRIMARY KEY,
    config JSONB NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
