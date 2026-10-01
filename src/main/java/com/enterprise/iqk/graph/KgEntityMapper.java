package com.enterprise.iqk.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** kg_entity 表 Mapper：按名称/别名检索与按类型查询，均强制租户过滤。 */
@Mapper
public interface KgEntityMapper extends BaseMapper<KgEntityRecord> {

    @Delete("DELETE FROM kg_entity WHERE tenant_id = #{tenantId} AND source_id = #{sourceId}")
    /** 按来源作用域删除实体（source_id 约定存 chatId），重建图谱前先清旧数据。 */
    int deleteBySource(@Param("tenantId") String tenantId,
                       @Param("sourceId") String sourceId);

    @Select("""
            SELECT * FROM kg_entity
            WHERE tenant_id = #{tenantId}
              AND (name LIKE CONCAT('%', #{keyword}, '%')
                   OR JSON_CONTAINS(aliases, JSON_QUOTE(#{keyword})))
            LIMIT #{limit}
            """)
    /** 名称模糊匹配或别名精确命中（JSON_CONTAINS），限量返回。 */
    List<KgEntityRecord> searchByName(@Param("tenantId") String tenantId,
                                       @Param("keyword") String keyword,
                                       @Param("limit") int limit);

    @Select("SELECT * FROM kg_entity WHERE tenant_id = #{tenantId} AND type = #{type}")
    /** 查询租户下指定类型的全部实体。 */
    List<KgEntityRecord> findByType(@Param("tenantId") String tenantId,
                                     @Param("type") String type);

    @Select("""
            SELECT * FROM kg_entity
            WHERE entity_id = #{entityId}
              AND tenant_id = #{tenantId}
            LIMIT 1
            """)
    /** 按租户 + 业务实体 ID 精确查一条。 */
    KgEntityRecord findByEntityId(@Param("tenantId") String tenantId,
                                  @Param("entityId") String entityId);

    @Select("""
            <script>
            SELECT * FROM kg_entity
            WHERE tenant_id = #{tenantId}
              AND (
              <foreach collection='keywords' item='kw' separator=' OR '>
                (name LIKE CONCAT('%', #{kw}, '%')
                 OR JSON_CONTAINS(aliases, JSON_QUOTE(#{kw})))
              </foreach>
              )
            LIMIT #{limit}
            </script>
            """)
    /** 多候选关键词批量 OR 检索（中文滑窗候选），关键词须先经 SqlLikeUtils 转义。 */
    List<KgEntityRecord> searchByKeywords(@Param("tenantId") String tenantId,
                                          @Param("keywords") List<String> keywords,
                                          @Param("limit") int limit);
}
