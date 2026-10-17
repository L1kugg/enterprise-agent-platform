package com.enterprise.iqk.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 用户侧知识库目录项（FR-R5-01）：上传/试搜选择器的数据源。
 * 只透出展示字段与真实统计，配置细节不出服务层。
 */
@Data
@Builder
public class KnowledgeBaseCatalogItemVO {
    private Long id;
    private String name;
    private String description;
    /** ACTIVE | DISABLED（目录只含 ACTIVE，字段留给前端兜底展示） */
    private String status;
    /** 归属文档数（真实聚合自 ingestion_job） */
    private long fileCount;
    /** 可用切片总数（真实聚合） */
    private long chunkCount;
}
