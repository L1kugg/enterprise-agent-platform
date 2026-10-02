package com.enterprise.iqk.evaluation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** eval_result 表 Mapper：按运行 ID 取 case 级结果明细。 */
@Mapper
public interface EvalResultMapper extends BaseMapper<EvalResultRecord> {

    @Select("""
            SELECT * FROM eval_result
            WHERE tenant_id = #{tenantId}
              AND run_id = #{runId}
            ORDER BY id ASC
            """)
    List<EvalResultRecord> findByTenantAndRunId(@Param("tenantId") String tenantId,
                                                @Param("runId") String runId);

    /** 删除数据集的全部 case 级结果明细，返回清理条数（供删除回执展示）。 */
    @Delete("""
            DELETE FROM eval_result
            WHERE tenant_id = #{tenantId}
              AND dataset_id = #{datasetId}
            """)
    int deleteByTenantAndDatasetId(@Param("tenantId") String tenantId,
                                   @Param("datasetId") String datasetId);
}
