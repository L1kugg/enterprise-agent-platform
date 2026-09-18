# 演示数据

本目录包含用于本地 RAG 验证的小型、可人工审阅的示例文档。

| 文件 | 用途 |
|---|---|
| `heat-safety-policy.txt` | 高温安全 RAG 场景的源文本。 |
| `heat-safety-policy.pdf` | 通过 `/ingestion/upload/{chatId}` 或 PDF 上传接口上传该 PDF。 |

示例内容刻意不含敏感信息，用于覆盖验证以下能力：

- PDF 上传与异步摄取。
- 带引用的检索命中。
- 证据片段展示。
- 空结果兜底行为。
- 租户隔离校验。
