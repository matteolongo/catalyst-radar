-- Provider tickers that named no configured company when the document was
-- stored. They are the evidence for universe candidates (ADR 0002). Before
-- this table they existed only as a joined string in raw_payload.
CREATE TABLE source_document_unresolved_tickers (
    source_document_id UUID NOT NULL REFERENCES source_documents (id) ON DELETE CASCADE,
    ticker VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_document_id, ticker)
);
CREATE INDEX ix_unresolved_tickers_ticker ON source_document_unresolved_tickers (ticker);

INSERT INTO source_document_unresolved_tickers (source_document_id, ticker)
SELECT DISTINCT sd.id, upper(btrim(t.raw))
FROM source_documents sd
CROSS JOIN LATERAL unnest(string_to_array(sd.raw_payload ->> 'tickers', ',')) AS t(raw)
WHERE upper(btrim(t.raw)) ~ '^[A-Z0-9][A-Z0-9.-]{0,15}$'
  AND NOT EXISTS (SELECT 1 FROM companies c WHERE c.ticker = upper(btrim(t.raw)))
ON CONFLICT DO NOTHING;
