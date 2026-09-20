package com.enterprise.iqk.graph;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("kg_entity")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/** kg_entity 表实体：知识图谱节点（课程/难度/主题等），按 tenant_id 租户隔离。 */
public class KgEntityRecord {
    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 业务实体 ID，租户内唯一 */
    private String entityId;
    /** 所属租户 ID */
    private String tenantId;
    /** 实体名称，检索时的主要匹配字段 */
    private String name;
    /** 实体类型，如 COURSE/TOPIC/DIFFICULTY */
    private String type;
    private String aliases;      // JSON 数组
    /** 实体描述 */
    private String description;
    /** 来源标识（如产出该实体的文档/任务 ID） */
    private String sourceId;
    /** 扩展元数据 JSON */
    private String metadataJson;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
}
