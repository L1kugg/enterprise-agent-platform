package com.enterprise.iqk.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 记忆事件表 Mapper：记录 CREATE / UPDATE / DELETE / EXPIRE / HIT / USE 留痕（库存英文值）。 */
@Mapper
public interface MemoryEventMapper extends BaseMapper<MemoryEventRecord> {

    @Select("SELECT * FROM memory_event WHERE memory_id = #{memoryId} ORDER BY created_at DESC")
    List<MemoryEventRecord> findByMemoryId(@Param("memoryId") String memoryId);
}
