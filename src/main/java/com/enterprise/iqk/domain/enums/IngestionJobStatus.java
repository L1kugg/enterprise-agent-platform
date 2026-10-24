package com.enterprise.iqk.domain.enums;

public enum IngestionJobStatus {
    PENDING,
    RUNNING,
    RETRY,
    SUCCEEDED,
    FAILED,
    /** 知识库同名文档被新版本替换：向量/关键词/图谱已清，任务行保留审计，文档清单与统计不再展示。 */
    SUPERSEDED
}
