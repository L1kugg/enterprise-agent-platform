package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.AnswerFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface AnswerFeedbackMapper extends BaseMapper<AnswerFeedback> {

    /**
     * 业务效果窗口汇总：反馈总数、好评（4-5）、点踩（1-2）、中评（3）。
     * 好评率由服务层计算（业务口径的"解决率"代理）。返回 Map 由服务层转换
     * （MyBatis 对 record 构造映射不可靠）。
     */
    @Select("""
            SELECT
              COUNT(*) AS total,
              COALESCE(SUM(CASE WHEN rating >= 4 THEN 1 ELSE 0 END), 0) AS positive,
              COALESCE(SUM(CASE WHEN rating <= 2 THEN 1 ELSE 0 END), 0) AS negative,
              COALESCE(SUM(CASE WHEN rating = 3 THEN 1 ELSE 0 END), 0) AS neutral
            FROM answer_feedback
            WHERE tenant_id = #{tenantId}
              AND created_at >= #{since}
            """)
    Map<String, Object> summarizeFeedback(@Param("tenantId") String tenantId,
                                          @Param("since") LocalDateTime since);

    /** 反馈按日趋势（服务层补零保证曲线连续）。 */
    @Select("""
            SELECT DATE(created_at) AS day,
                   COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN rating >= 4 THEN 1 ELSE 0 END), 0) AS positive,
                   COALESCE(SUM(CASE WHEN rating <= 2 THEN 1 ELSE 0 END), 0) AS negative
            FROM answer_feedback
            WHERE tenant_id = #{tenantId}
              AND created_at >= #{since}
            GROUP BY DATE(created_at)
            ORDER BY day
            """)
    List<Map<String, Object>> feedbackTrendByDay(@Param("tenantId") String tenantId,
                                                 @Param("since") LocalDateTime since);

    /** 点踩原因 TopN（含未标注行；原因采集上线前的历史行全部落"未标注"）。 */
    @Select("""
            SELECT COALESCE(reason, '未标注') AS reason,
                   COUNT(*) AS count
            FROM answer_feedback
            WHERE tenant_id = #{tenantId}
              AND created_at >= #{since}
              AND rating <= 2
            GROUP BY COALESCE(reason, '未标注')
            ORDER BY count DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> topDislikeReasons(@Param("tenantId") String tenantId,
                                                @Param("since") LocalDateTime since,
                                                @Param("limit") int limit);
}
