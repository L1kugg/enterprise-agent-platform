package com.enterprise.iqk.evaluation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** eval_dataset 表 Mapper：数据集按租户查询与基线运行标记。 */
@Mapper
public interface EvalDatasetMapper extends BaseMapper<EvalDatasetRecord> {

    @Select("""
            SELECT * FROM eval_dataset
            WHERE tenant_id = #{tenantId}
              AND dataset_id = #{datasetId}
            LIMIT 1
            """)
    EvalDatasetRecord findByTenantAndDatasetId(@Param("tenantId") String tenantId,
                                               @Param("datasetId") String datasetId);

    @Select("""
            SELECT d.* FROM eval_dataset d
            WHERE d.tenant_id = #{tenantId}
            ORDER BY d.updated_at DESC
            """)
    List<EvalDatasetRecord> findByTenant(@Param("tenantId") String tenantId);

    @Update("""
            UPDATE eval_dataset
            SET baseline_run_id = #{baselineRunId}, updated_at = NOW()
            WHERE tenant_id = #{tenantId}
              AND dataset_id = #{datasetId}
            """)
    int updateBaselineRunId(@Param("tenantId") String tenantId,
                            @Param("datasetId") String datasetId,
                            @Param("baselineRunId") String baselineRunId);

    /** 删除数据集本身，返回清理条数（0 = 不存在，由服务层先行校验）。 */
    @Delete("""
            DELETE FROM eval_dataset
            WHERE tenant_id = #{tenantId}
              AND dataset_id = #{datasetId}
            """)
    int deleteByTenantAndDatasetId(@Param("tenantId") String tenantId,
                                   @Param("datasetId") String datasetId);
}
