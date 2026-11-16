-- How a company entered the universe (ADR 0002). Seeded companies are tracked
-- from the start (tracked_since NULL); a promoted company records when it was
-- chosen, because choosing it used information from that moment.
ALTER TABLE companies
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'SEED' CHECK (origin IN ('SEED', 'PROMOTED')),
    ADD COLUMN tracked_since TIMESTAMPTZ,
    ADD CONSTRAINT ck_companies_promoted_tracked_since CHECK (origin = 'SEED' OR tracked_since IS NOT NULL);
