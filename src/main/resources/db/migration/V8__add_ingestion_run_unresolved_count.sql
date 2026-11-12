-- documents_new keeps meaning "stored by this run" (queued plus unresolved).
-- This column separates the stored documents that name no configured
-- company and were therefore never queued for extraction.
ALTER TABLE ingestion_runs ADD COLUMN documents_unresolved INTEGER NOT NULL DEFAULT 0;
