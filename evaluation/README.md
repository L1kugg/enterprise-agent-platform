# 评测与回归测试

## 数据集

| 文件 | 规模 | 用途 |
|---|---|---|
| `dataset.json` | 4 条用例 | 轻量 CI 回归门禁 |
| `dataset.large.json` | 自动生成 | 夜间回归（由 `scripts/generate_eval_dataset.py` 生成） |

每条用例包含 `expected_keywords`、`forbidden_keywords`、`category` 以及可选的 `expected_citations`。

## 预测文件

| 文件 | 来源 |
|---|---|
| `predictions.sample.json` | 人工编写的本地测试样本 |
| `predictions.generated.json` | 由 `scripts/generate_eval_predictions.py` 生成（CI） |

## 指标

回归门禁（`scripts/run_regression.py`）度量以下指标：

| 指标 | 说明 |
|---|---|
| Correctness Rate | 预期关键词命中率达到阈值的用例占比 |
| Citation Hit Rate | 引用正确的 RAG 用例占比 |
| Hallucination Rate | 包含禁用关键词的用例占比 |
| Failure Rate | 状态错误或答案为空的用例占比 |
| First Token Latency P95 | 首 token 延迟的第 95 百分位（毫秒） |

平台 Evaluation Studio API 持久化并展示以下指标：

| 指标 | 说明 |
|---|---|
| Retrieval Hit Rate | 混合检索返回证据或匹配预期引用的用例占比 |
| Citation Coverage | 返回引用对预期引用要素的覆盖度 |
| Answer Faithfulness | 最终答案的引用标记支持度代理指标 |
| Average Latency | 每条用例的平均端到端评测延迟 |
| Failure Rate | 本次运行中失败用例的占比 |
| Run Score | 检索、引用、关键词与忠实度指标的加权得分 |

## 复现方法

```bash
# Generate predictions from dataset
python3 scripts/generate_eval_predictions.py \
  --dataset evaluation/dataset.json \
  --output evaluation/predictions.generated.json

# Run regression gate
python3 scripts/run_regression.py \
  --dataset evaluation/dataset.json \
  --predictions evaluation/predictions.generated.json \
  --threshold 0.70 \
  --correctness-threshold 0.75 \
  --citation-hit-threshold 0.80 \
  --hallucination-max-rate 0.15 \
  --failure-max-rate 0.10
```

报告写入 `reports/regression/latest.json` 与 `reports/regression/latest.md`。

## Evaluation Studio API

启动整套服务后，生成一份最新的报告文件：

```bash
make demo
make eval-demo
```

报告会覆盖写入 `evaluation/reports/latest-evaluation-report.md`。

API 端点：

```bash
POST /ai/evaluation/datasets
GET  /ai/evaluation/datasets
POST /ai/evaluation/datasets/{datasetId}/runs
GET  /ai/evaluation/datasets/{datasetId}/comparison
POST /ai/evaluation/runs/{runId}/baseline
GET  /ai/evaluation/runs/{runId}/report
```

## Evaluation Studio 汇总

回归运行结束后，可生成面向评审者的评分卡，包含质量分档、基线差值、门禁检查与风险队列：

```bash
python3 scripts/generate_eval_studio.py \
  --current reports/regression/latest.json \
  --output-json evaluation/reports/studio-summary.json \
  --output-md evaluation/reports/studio-summary.md
```

使用 `--baseline <path>` 可将当前运行与此前保存的回归报告进行对比。

## CI 集成

- **ci.yml**：每次向 `main` 推送代码或提交 PR 时运行回归门禁
- **nightly-regression.yml**：每天 18:00（UTC）运行全量大数据集回归
