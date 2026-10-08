ALTER TABLE eval_case
  ADD COLUMN expected_document_ids_json TEXT NULL AFTER expected_citations_json,
  ADD COLUMN expected_chunk_ids_json TEXT NULL AFTER expected_document_ids_json;

ALTER TABLE eval_result
  ADD COLUMN retrieved_results_json TEXT NULL AFTER evidence_json,
  ADD COLUMN retrieval_metrics_applicable TINYINT(1) NOT NULL DEFAULT 0 AFTER score,
  ADD COLUMN retrieval_metric_level VARCHAR(16) NOT NULL DEFAULT 'none' AFTER retrieval_metrics_applicable,
  ADD COLUMN recall_at_k DOUBLE NOT NULL DEFAULT 0 AFTER retrieval_metric_level,
  ADD COLUMN mrr_at_k DOUBLE NOT NULL DEFAULT 0 AFTER recall_at_k,
  ADD COLUMN precision_at_k DOUBLE NOT NULL DEFAULT 0 AFTER mrr_at_k;

ALTER TABLE eval_run
  ADD COLUMN retrieval_metrics_cases INT NOT NULL DEFAULT 0 AFTER retrieval_hit_rate,
  ADD COLUMN retrieval_metric_level VARCHAR(16) NOT NULL DEFAULT 'none' AFTER retrieval_metrics_cases,
  ADD COLUMN recall_at_k_rate DOUBLE NOT NULL DEFAULT 0 AFTER retrieval_metric_level,
  ADD COLUMN mrr_at_k DOUBLE NOT NULL DEFAULT 0 AFTER recall_at_k_rate,
  ADD COLUMN precision_at_k_rate DOUBLE NOT NULL DEFAULT 0 AFTER mrr_at_k;

ALTER TABLE eval_result
  RENAME COLUMN answer_faithfulness TO citation_marker_coverage;

ALTER TABLE eval_run
  RENAME COLUMN answer_faithfulness_score TO citation_marker_coverage_rate;
