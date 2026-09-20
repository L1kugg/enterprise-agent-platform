#!/usr/bin/env python3
"""Offline contract check for the KnowledgeOps <-> ragproof integration."""

import json
from pathlib import Path


# 仓库根目录，用于定位 ragproof 契约的适配器配置与策略文件
ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = ROOT / "evaluation" / "ragproof" / "knowledgeops-react.json"
POLICY_PATH = ROOT / "evaluation" / "ragproof" / "policy.json"


# 离线校验契约：适配器字段映射、必需响应字段与零错误率阈值逐项断言
def main() -> None:
    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
    adapter = config["adapter"]

    assert adapter["endpoint"] == "/ai/react/chat"
    assert adapter["json_field"] == "prompt"
    assert adapter["answer_path"] == "answer"
    assert adapter["contexts_path"] == "evidence"
    assert adapter["citations_path"] == "citations"
    assert adapter["fallback_path"] == "fallback"
    assert adapter["expected_fallback"] is False
    assert set(config["required_fields"]) == {"successful_requests", "answers", "contexts", "citations"}
    assert policy["max_thresholds"]["error_rate"] == 0.0

    # 再校验数据集文件：样本量达标且每条都带 id/question/ground_truth
    dataset = CONFIG_PATH.parent / config["dataset"]
    cases = [json.loads(line) for line in dataset.read_text(encoding="utf-8").splitlines() if line.strip()]
    assert len(cases) >= config["min_sample_count"]
    assert all(case.get("id") and case.get("question") and case.get("ground_truth") for case in cases)
    print(f"ragproof contract verified: {len(cases)} cases")


if __name__ == "__main__":
    main()
