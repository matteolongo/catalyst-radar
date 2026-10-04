ALTER TABLE operation_run_issues ADD COLUMN provider VARCHAR(64);

UPDATE operation_run_issues issue
SET provider = attributed.provider
FROM (
  SELECT operation_run_id, error_code, MIN(provider) AS provider
  FROM ingestion_runs
  WHERE operation_run_id IS NOT NULL AND error_code IS NOT NULL
  GROUP BY operation_run_id, error_code
  HAVING COUNT(DISTINCT provider) = 1
) attributed
WHERE issue.phase = 'INGESTION'
  AND issue.operation_run_id = attributed.operation_run_id
  AND issue.error_code = attributed.error_code;

UPDATE operation_run_issues issue
SET provider = attributed.provider
FROM (
  SELECT attempt.operation_run_id, attempt.source_document_id, model.error_code,
         MIN(model.provider) AS provider
  FROM document_processing_attempts attempt
  JOIN model_runs model ON model.processing_attempt_id = attempt.id
  WHERE model.success = FALSE AND model.error_code IS NOT NULL
  GROUP BY attempt.operation_run_id, attempt.source_document_id, model.error_code
  HAVING COUNT(DISTINCT model.provider) = 1
) attributed
WHERE issue.phase = 'PROCESSING'
  AND issue.operation_run_id = attributed.operation_run_id
  AND issue.source_document_id = attributed.source_document_id
  AND issue.error_code = attributed.error_code;
