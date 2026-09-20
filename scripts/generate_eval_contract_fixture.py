#!/usr/bin/env python3
"""Generate deterministic fixtures for testing the regression evaluator contract.

This script intentionally reads expected fields. Its output must never be used as
evidence of model quality; live quality gates use eval_live_runner.py instead.
"""

import argparse
import json
from pathlib import Path


# 生成"必然通过"的合成预测：答案直接用期望关键词拼成，延迟按序号递变，
# 只用于验证回归评测器的契约，不能当作模型质量证据
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="evaluation/dataset.large.json")
    parser.add_argument("--output", default="evaluation/predictions.contract.json")
    args = parser.parse_args()

    dataset = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    predictions = []

    for index, case in enumerate(dataset):
        keywords = case.get("expected_keywords", [])
        answer = " ".join(keywords) if keywords else "已执行并返回结果。"

        # 有期望引用直接复用；rag 类用例补一条合成引用满足引用检查，其余不带引用
        expected_citations = case.get("expected_citations", [])
        is_rag = str(case.get("category", "")).startswith("rag")
        if expected_citations:
            citations = [str(item) for item in expected_citations if str(item).strip()]
        elif is_rag:
            citations = [f"source=contract/{case.get('id', index)}.md, chunk=1"]
        else:
            citations = []

        first_token_latency_ms = 180 + (index % 120)
        total_latency_ms = first_token_latency_ms + 380 + (index % 90)

        predictions.append({
            "id": case.get("id"),
            "prediction_source": "synthetic_contract_fixture",
            "status": "ok",
            "answer": answer,
            "citations": citations,
            "first_token_latency_ms": first_token_latency_ms,
            "total_latency_ms": total_latency_ms,
        })

    out = Path(args.output)
    out.write_text(json.dumps(predictions, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"written contract fixture: {out} ({len(predictions)} records)")


if __name__ == "__main__":
    main()
