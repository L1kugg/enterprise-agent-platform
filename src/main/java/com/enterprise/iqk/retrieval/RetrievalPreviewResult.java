package com.enterprise.iqk.retrieval;

import java.util.List;

/**
 * 试搜结果：合并去重后的 topK 命中 + 降级路清单。
 * degradedSources 非空表示有本地检索路异常被跳过（结果可能不完整），前端据此提示。
 */
public record RetrievalPreviewResult(
        List<RetrievalPreviewItem> items,
        List<String> degradedSources) {
}
