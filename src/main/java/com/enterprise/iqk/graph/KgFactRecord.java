package com.enterprise.iqk.graph;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("kg_fact")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/** kg_fact 表实体：知识图谱事实三元组（主语-谓语-宾语），按 tenant_id 租户隔离。 */
public class KgFactRecord {
    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 业务事实 ID */
    private String factId;
    /** 所属租户 ID */
    private String tenantId;
    /** 主语（实体名称） */
    private String subject;
    /** 谓词（关系/属性名） */
    private String predicate;
    /** 宾语（值或另一实体名称） */
    private String object;
    /** 事实有效期起点 */
    private LocalDate validFrom;
    /** 事实有效期终点 */
    private LocalDate validTo;
    /** 置信度（0-1），检索默认按其倒序 */
    private Double confidence;
    /** 来源标识 */
    private String source;
    /** 扩展元数据 JSON */
    private String metadataJson;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
}
