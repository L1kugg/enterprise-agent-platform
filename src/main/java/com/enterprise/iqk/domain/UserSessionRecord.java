package com.enterprise.iqk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 服务端登录会话：人类通道（Session + Cookie）的唯一持久化状态。
 * 库里只存会话 ID 的 sha256（模式同 refresh_tokens）——Cookie 携带原文，泄库不可用。
 * 会话可被精确吊销（登出 / 管理员踢下线），expires_at 到期后查找即不可见。
 */
@TableName("user_session")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSessionRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionHash;
    private String principal;
    private String tenantId;
    /** 角色逗号串（与 refresh_tokens.roles 同风格），登录时固化 */
    private String rolesCsv;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
}
