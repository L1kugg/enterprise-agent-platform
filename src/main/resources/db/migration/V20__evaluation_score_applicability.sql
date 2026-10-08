ALTER TABLE eval_result
  ADD COLUMN keyword_score_applicable TINYINT(1) NOT NULL DEFAULT 1,
  ADD COLUMN citation_coverage_applicable TINYINT(1) NOT NULL DEFAULT 1;

UPDATE eval_result result
JOIN eval_case evalCase
  ON evalCase.tenant_id = result.tenant_id
 AND evalCase.dataset_id = result.dataset_id
 AND evalCase.case_id = result.case_id
SET result.keyword_score_applicable = (
       COALESCE(JSON_LENGTH(CASE WHEN JSON_VALID(evalCase.expected_keywords_json)
             THEN evalCase.expected_keywords_json END), 0) > 0
       OR COALESCE(JSON_LENGTH(CASE WHEN JSON_VALID(evalCase.forbidden_keywords_json)
             THEN evalCase.forbidden_keywords_json END), 0) > 0
     ),
    result.citation_coverage_applicable = COALESCE(JSON_LENGTH(CASE WHEN JSON_VALID(
       evalCase.expected_citations_json) THEN evalCase.expected_citations_json END), 0) > 0;
