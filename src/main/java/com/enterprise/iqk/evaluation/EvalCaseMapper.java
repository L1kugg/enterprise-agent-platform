package com.enterprise.iqk.evaluation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** eval_case 表 Mapper：按数据集取排序后的用例列表。 */
@Mapper
public interface EvalCaseMapper extends BaseMapper<EvalCaseRecord> {

    @Select("""
            SELECT * FROM eval_case
            WHERE tenant_id = #{tenantId}
              AND dataset_id = #{datasetId}
            ORDER BY sort_order ASC, id ASC
            """)
    List<EvalCaseRecord> findByTenantAndDatasetId(@Param("tenantId") String tenantId,
                                                  @Param("datasetId") String datasetId);

    /** 删除数据集的全部用例，返回清理条数（供删除回执展示）。 */
    @Delete("""
            DELETE FROM eval_case
            WHERE tenant_id = #{tenantId}
              AND dataset_id = #{datasetId}
            """)
    int deleteByTenantAndDatasetId(@Param("tenantId") String tenantId,
                                   @Param("datasetId") String datasetId);
}
