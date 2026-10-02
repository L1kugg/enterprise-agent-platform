package com.enterprise.iqk.agent.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** agent_task 表 Mapper：任务的按租户/按 ID 查询与状态更新（checkstyle 禁止 SQL 拼接，全部参数化）。 */
@Mapper
public interface AgentTaskMapper extends BaseMapper<AgentTaskRecord> {

    @Select("SELECT * FROM agent_task WHERE task_id = #{taskId}")
    AgentTaskRecord findByTaskId(@Param("taskId") String taskId);

    @Select("SELECT * FROM agent_task WHERE tenant_id = #{tenantId} AND task_id = #{taskId}")
    AgentTaskRecord findByTenantAndTaskId(@Param("tenantId") String tenantId,
                                          @Param("taskId") String taskId);

    @Select("SELECT * FROM agent_task WHERE tenant_id = #{tenantId} ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<AgentTaskRecord> findByTenant(@Param("tenantId") String tenantId,
                                       @Param("offset") long offset,
                                       @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM agent_task WHERE tenant_id = #{tenantId}")
    long countByTenant(@Param("tenantId") String tenantId);

    @Select("SELECT * FROM agent_task WHERE tenant_id = #{tenantId} AND type = #{type} ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<AgentTaskRecord> findByTenantAndType(@Param("tenantId") String tenantId,
                                              @Param("type") String type,
                                              @Param("offset") long offset,
                                              @Param("limit") int limit);

    @Update("UPDATE agent_task SET status = #{status}, updated_at = NOW() WHERE task_id = #{taskId}")
    int updateStatus(@Param("taskId") String taskId, @Param("status") String status);

    @Update("UPDATE agent_task SET status = #{status}, final_output = #{finalOutput}, updated_at = NOW() WHERE task_id = #{taskId}")
    int completeTask(@Param("taskId") String taskId,
                     @Param("status") String status,
                     @Param("finalOutput") String finalOutput);

    /** 孤儿任务排查：停留在非终态且 updated_at 早于截止时间的任务（断连/进程重启遗留）。 */
    @Select("SELECT * FROM agent_task WHERE status NOT IN ('DONE', 'FAILED') AND updated_at < #{cutoff} ORDER BY updated_at ASC LIMIT #{limit}")
    List<AgentTaskRecord> findStaleTasks(@Param("cutoff") LocalDateTime cutoff,
                                         @Param("limit") int limit);

    /** 启动 sweep：进程启动时遗留的全部非终态任务（无时间阈值——上一进程的中断现场）。 */
    @Select("SELECT * FROM agent_task WHERE status NOT IN ('DONE', 'FAILED') ORDER BY updated_at ASC LIMIT #{limit}")
    List<AgentTaskRecord> findNonTerminalTasks(@Param("limit") int limit);

    /** 守卫式收尾：仅当任务仍在非终态时置 FAILED，返回是否实际更新（防"取消与完成竞态"把 DONE 覆盖成 FAILED）。 */
    @Update("UPDATE agent_task SET status = 'FAILED', final_output = #{reason}, updated_at = NOW() WHERE task_id = #{taskId} AND status NOT IN ('DONE', 'FAILED')")
    int failIfNotTerminal(@Param("taskId") String taskId,
                          @Param("reason") String reason);
}
