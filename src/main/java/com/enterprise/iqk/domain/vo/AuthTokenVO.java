package com.enterprise.iqk.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AuthTokenVO {
    private Integer ok;
    private String msg;
    private String token;
    private String refreshToken;
    private String tenantId;
    /** 首个角色（如 ADMIN / USER），前端据此显隐管理员 UI（如删除文档按钮）。 */
    private String role;
    private Long expiresInSeconds;
    private LocalDateTime refreshExpiresAt;
    private Boolean refreshWillExpireSoon;
}
