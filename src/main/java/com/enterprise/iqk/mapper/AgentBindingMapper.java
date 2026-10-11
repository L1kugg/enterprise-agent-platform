package com.enterprise.iqk.mapper;

import com.enterprise.iqk.domain.agent.AgentBindingRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 智能体绑定关系通用 Mapper：动态表名来自 AgentBindingTable 枚举白名单，
 * 所有语句都以 (tenant_id, agent_id) 为界，保证租户与智能体双重隔离。
 */
@Mapper
public interface AgentBindingMapper {

    @Delete("DELETE FROM ${table} WHERE tenant_id = #{tenantId} AND agent_id = #{agentId}")
    int deleteByAgent(@Param("table") String table,
                      @Param("tenantId") String tenantId,
                      @Param("agentId") Long agentId);

    @Insert("""
            <script>
            INSERT INTO ${table}
              (tenant_id, agent_id, resource_id, resource_type, binding_order,
               execution_confirm, channel, extra_json, created_at, updated_at)
            VALUES
            <foreach collection="rows" item="row" separator=",">
              (#{tenantId}, #{agentId}, #{row.resourceId}, #{row.resourceType}, #{row.bindingOrder},
               #{row.executionConfirm}, #{row.channel}, #{row.extraJson}, NOW(), NOW())
            </foreach>
            </script>
            """)
    int insertBatch(@Param("table") String table,
                    @Param("tenantId") String tenantId,
                    @Param("agentId") Long agentId,
                    @Param("rows") List<AgentBindingRow> rows);

    @Select("""
            SELECT resource_id AS resourceId, resource_type AS resourceType,
                   binding_order AS bindingOrder, execution_confirm AS executionConfirm,
                   channel, extra_json AS extraJson
            FROM ${table}
            WHERE tenant_id = #{tenantId} AND agent_id = #{agentId}
            ORDER BY binding_order, id
            """)
    List<AgentBindingRow> findByAgent(@Param("table") String table,
                                      @Param("tenantId") String tenantId,
                                      @Param("agentId") Long agentId);
}
