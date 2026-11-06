package com.enterprise.iqk.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AuthTokenVO {
    private Integer ok;
    private String msg;
    /** JWT 访问令牌（程序化通道 /auth/token 签发；密码登录走 Session Cookie，恒为 null） */
    private String token;
    /** 旧刷新令牌字段：Session 模式下不再使用（保留字段避免破坏既有响应契约） */
    private String refreshToken;
    private String tenantId;
    /** 登录用户名（密码登录 /auth/me 返回） */
    private String username;
    /** 首个角色（如 ADMIN / USER），前端据此显隐管理员 UI（如删除文档按钮）。 */
    private String role;
    private Long expiresInSeconds;
    private LocalDateTime refreshExpiresAt;
    private Boolean refreshWillExpireSoon;
}
