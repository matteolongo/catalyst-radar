-- Downside strength (0..100) from negative contributions only. The headline
-- score nets negatives against positives and clamps at zero, so a company
-- dominated by negative catalysts was previously indistinguishable from a
-- quiet one when ranking or reading snapshots.
ALTER TABLE catalyst_snapshots
    ADD COLUMN downside_score DOUBLE PRECISION NOT NULL DEFAULT 0
        CHECK (downside_score >= 0 AND downside_score <= 100);

-- Scoring reads events by their effective time within the decay horizon.
CREATE INDEX ix_events_company_effective_time
    ON events (company_id, (COALESCE(event_timestamp, discovered_at)) DESC);
