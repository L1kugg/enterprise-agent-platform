package com.enterprise.iqk.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** kg_fact 表 Mapper：关键词检索与按主语查询，均强制租户过滤。 */
@Mapper
public interface KgFactMapper extends BaseMapper<KgFactRecord> {

    @Delete("DELETE FROM kg_fact WHERE tenant_id = #{tenantId} AND source = #{source}")
    /** 按来源删除事实（source 约定存 chatId）。 */
    int deleteBySource(@Param("tenantId") String tenantId,
                       @Param("source") String source);

    @Select("""
            SELECT * FROM kg_fact
            WHERE tenant_id = #{tenantId}
              AND (subject LIKE CONCAT('%', #{keyword}, '%')
                   OR object LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY confidence DESC
            LIMIT #{limit}
            """)
    /** 主语或宾语模糊匹配，按置信度倒序限量返回。 */
    List<KgFactRecord> searchByKeyword(@Param("tenantId") String tenantId,
                                        @Param("keyword") String keyword,
                                        @Param("limit") int limit);

    @Select("SELECT * FROM kg_fact WHERE tenant_id = #{tenantId} AND subject = #{subject}")
    /** 查询租户下指定主语的全部事实。 */
    List<KgFactRecord> findBySubject(@Param("tenantId") String tenantId,
                                      @Param("subject") String subject);

    @Select("""
            <script>
            SELECT * FROM kg_fact
            WHERE tenant_id = #{tenantId}
              AND (
              <foreach collection='keywords' item='kw' separator=' OR '>
                (subject LIKE CONCAT('%', #{kw}, '%')
                 OR object LIKE CONCAT('%', #{kw}, '%'))
              </foreach>
              )
            ORDER BY confidence DESC
            LIMIT #{limit}
            </script>
            """)
    /** 多候选关键词批量 OR 检索主语/宾语（中文滑窗候选），关键词须先经 SqlLikeUtils 转义。 */
    List<KgFactRecord> searchByKeywords(@Param("tenantId") String tenantId,
                                        @Param("keywords") List<String> keywords,
                                        @Param("limit") int limit);
}
