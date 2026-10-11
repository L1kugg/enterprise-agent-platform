package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.agent.AgentInvocationLogRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentInvocationLogMapper extends BaseMapper<AgentInvocationLogRecord> {
}
