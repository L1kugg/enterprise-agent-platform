package com.enterprise.iqk.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** kg_relation 表 Mapper：按实体/端点/类型查询关系边，均强制租户过滤。 */
@Mapper
public interface KgRelationMapper extends BaseMapper<KgRelationRecord> {

    @Delete("DELETE FROM kg_relation WHERE tenant_id = #{tenantId} AND evidence_id = #{evidenceId}")
    /** 按证据来源删除关系边（evidence_id 约定存 chatId），先删边再删点。 */
    int deleteByEvidence(@Param("tenantId") String tenantId,
                         @Param("evidenceId") String evidenceId);

    @Select("""
            SELECT r.* FROM kg_relation r
            WHERE r.tenant_id = #{tenantId}
              AND (r.source_entity_id = #{entityId} OR r.target_entity_id = #{entityId})
            """)
    /** 查询实体作为源或目标参与的全部关系（一跳邻居的边集）。 */
    List<KgRelationRecord> findRelations(@Param("tenantId") String tenantId,
                                          @Param("entityId") String entityId);

    @Select("""
            SELECT r.* FROM kg_relation r
            WHERE r.tenant_id = #{tenantId}
              AND r.source_entity_id = #{sourceId}
              AND r.target_entity_id = #{targetId}
            """)
    /** 查询指定源 → 目标端点之间的有向关系。 */
    List<KgRelationRecord> findDirectRelation(@Param("tenantId") String tenantId,
                                               @Param("sourceId") String sourceId,
                                               @Param("targetId") String targetId);

    @Select("""
            SELECT r.* FROM kg_relation r
            WHERE r.tenant_id = #{tenantId}
              AND r.relation_type = #{relationType}
            """)
    /** 查询租户下指定类型的全部关系。 */
    List<KgRelationRecord> findByType(@Param("tenantId") String tenantId,
                                       @Param("relationType") String relationType);
}
