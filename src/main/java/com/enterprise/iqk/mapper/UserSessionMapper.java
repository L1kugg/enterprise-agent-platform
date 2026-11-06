package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.UserSessionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSessionRecord> {

    @Select("""
            SELECT * FROM user_session
            WHERE session_hash = #{sessionHash}
              AND revoked_at IS NULL
              AND expires_at > NOW()
            LIMIT 1
            """)
    UserSessionRecord findActiveByHash(@Param("sessionHash") String sessionHash);

    /** 条件更新式吊销：幂等，重复登出/并发登出只成功一次。 */
    @Update("""
            UPDATE user_session
            SET revoked_at = COALESCE(revoked_at, #{revokedAt})
            WHERE id = #{id}
              AND revoked_at IS NULL
            """)
    int revoke(@Param("id") Long id, @Param("revokedAt") LocalDateTime revokedAt);

    /** 按人全下线（管理员踢人/改密后兜底）：该租户该主体的全部活跃会话一起失效。 */
    @Update("""
            UPDATE user_session
            SET revoked_at = COALESCE(revoked_at, #{revokedAt})
            WHERE tenant_id = #{tenantId}
              AND principal = #{principal}
              AND revoked_at IS NULL
            """)
    int revokeAllForPrincipal(@Param("tenantId") String tenantId,
                              @Param("principal") String principal,
                              @Param("revokedAt") LocalDateTime revokedAt);
}
