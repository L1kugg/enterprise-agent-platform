# 演示数据

本目录包含小体积、便于审查的示例文档，用于本地 RAG 验证。

| 文件 | 用途 |
|---|---|
| `heat-safety-policy.txt` | 高温安全 RAG 场景的源文本。 |
| `heat-safety-policy.pdf` | 通过 `/ingestion/upload/{chatId}` 或 PDF 端点上传该文件。 |

示例内容刻意保持非敏感性，用于验证以下能力：

- PDF 上传与异步入库。
- 带引用的检索命中。
- 证据片段展示。
- 空结果回退行为。
- 租户隔离校验。
