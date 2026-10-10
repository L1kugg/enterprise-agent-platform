package com.enterprise.iqk.domain.platform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** 系统管理角色 VO：code 对应 roles.role_name，权限来自 permissions 关联。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformRoleRecord {
    private Long id;
    private String code;
    private String name;
    private String description;
    private List<String> permissions;
    private Boolean builtin;
    private LocalDateTime createdAt;
}
