package com.enterprise.iqk.domain.platform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** 系统管理用户 VO：不包含密码哈希，角色名由 user_roles 聚合。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformUserRecord {
    private Long id;
    private String username;
    private String displayName;
    private String tenantId;
    private List<String> roles;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
