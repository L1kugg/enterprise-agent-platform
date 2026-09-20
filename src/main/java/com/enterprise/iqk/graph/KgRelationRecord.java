package com.enterprise.iqk.graph;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("kg_relation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/** kg_relation 表实体：知识图谱有向关系边（源实体 → 目标实体），按 tenant_id 租户隔离。 */
public class KgRelationRecord {
    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 业务关系 ID */
    private String relationId;
    /** 所属租户 ID */
    private String tenantId;
    /** 源实体 ID（关系的起点） */
    private String sourceEntityId;
    /** 目标实体 ID（关系的终点） */
    private String targetEntityId;
    /** 关系类型 */
    private String relationType;
    /** 证据来源 ID */
    private String evidenceId;
    /** 关系权重 */
    private Double weight;
    /** 扩展元数据 JSON */
    private String metadataJson;
    /** 创建时间 */
    private LocalDateTime createdAt;
}
