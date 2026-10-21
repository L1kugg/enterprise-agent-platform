package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.DocumentSegmentRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 文档分段 Mapper（R8）：读写按 tenant+chat/job 圈定。 */
@Mapper
public interface DocumentSegmentMapper extends BaseMapper<DocumentSegmentRecord> {

    @Select("""
            SELECT * FROM document_segment
            WHERE tenant_id = #{tenantId} AND chat_id = #{chatId}
            ORDER BY seg_index
            """)
    List<DocumentSegmentRecord> findByChat(@Param("tenantId") String tenantId,
                                           @Param("chatId") String chatId);

    @Select("""
            SELECT * FROM document_segment
            WHERE tenant_id = #{tenantId} AND chat_id = #{chatId}
            ORDER BY seg_index
            LIMIT #{offset}, #{limit}
            """)
    List<DocumentSegmentRecord> pageByChat(@Param("tenantId") String tenantId,
                                           @Param("chatId") String chatId,
                                           @Param("offset") long offset,
                                           @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM document_segment
            WHERE tenant_id = #{tenantId} AND chat_id = #{chatId}
            """)
    long countByChat(@Param("tenantId") String tenantId,
                     @Param("chatId") String chatId);

    @Select("""
            SELECT * FROM document_segment
            WHERE tenant_id = #{tenantId} AND job_id = #{jobId} AND seg_index = #{segIndex}
            LIMIT 1
            """)
    DocumentSegmentRecord findByJobAndIndex(@Param("tenantId") String tenantId,
                                            @Param("jobId") String jobId,
                                            @Param("segIndex") int segIndex);

    @Delete("""
            DELETE FROM document_segment
            WHERE tenant_id = #{tenantId} AND chat_id = #{chatId}
            """)
    int deleteByChat(@Param("tenantId") String tenantId,
                     @Param("chatId") String chatId);

    @Delete("""
            DELETE FROM document_segment
            WHERE tenant_id = #{tenantId} AND job_id = #{jobId}
            """)
    int deleteByJob(@Param("tenantId") String tenantId,
                    @Param("jobId") String jobId);

    @Select("""
            SELECT COALESCE(MAX(seg_index), -1) FROM document_segment
            WHERE tenant_id = #{tenantId} AND chat_id = #{chatId}
            """)
    int maxSegIndex(@Param("tenantId") String tenantId,
                    @Param("chatId") String chatId);
}
