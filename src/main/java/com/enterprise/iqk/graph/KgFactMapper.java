package com.enterprise.iqk.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** kg_fact 表 Mapper：关键词检索与按主语查询，均强制租户过滤。 */
@Mapper
public interface KgFactMapper extends BaseMapper<KgFactRecord> {

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
}
