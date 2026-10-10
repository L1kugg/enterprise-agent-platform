package com.enterprise.iqk.service.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.iqk.domain.UserAccount;
import com.enterprise.iqk.domain.platform.PlatformRoleRecord;
import com.enterprise.iqk.domain.platform.PlatformUserRecord;
import com.enterprise.iqk.mapper.PermissionMapper;
import com.enterprise.iqk.mapper.PlatformRoleMapper;
import com.enterprise.iqk.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 平台用户/角色管理：复用 users/roles/permissions 原模型，所有鉴权仍由后端执行。 */
@Service
@RequiredArgsConstructor
public class PlatformAdminService {
    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
    private static final Set<String> BUILTIN_ROLES = Set.of("ADMIN", "USER", "OPS");

    private final UserAccountMapper userMapper;
    private final PlatformRoleMapper roleMapper;
    private final PermissionMapper permissionMapper;

    public List<PlatformUserRecord> listUsers(String search, int page, int pageSize) {
        String keyword = StringUtils.hasText(search) ? search.trim() : null;
        LambdaQueryWrapper<UserAccount> wrapper = new LambdaQueryWrapper<UserAccount>()
                .like(StringUtils.hasText(keyword), UserAccount::getUsername, keyword)
                .orderByDesc(UserAccount::getUpdatedAt);
        List<UserAccount> users = userMapper.selectList(wrapper);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        int from = Math.min((safePage - 1) * safeSize, users.size());
        int to = Math.min(from + safeSize, users.size());
        List<PlatformUserRecord> result = new ArrayList<>();
        for (UserAccount user : users.subList(from, to)) result.add(toUser(user));
        return result;
    }

    public long countUsers(String search) {
        String keyword = StringUtils.hasText(search) ? search.trim() : null;
        return userMapper.selectCount(new LambdaQueryWrapper<UserAccount>()
                .like(StringUtils.hasText(keyword), UserAccount::getUsername, keyword));
    }

    @Transactional
    public PlatformUserRecord updateUser(Long id, String displayName, String status, List<String> roles) {
        UserAccount user = userMapper.selectById(id);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        if (displayName != null) {
            user.setDisplayName(displayName.isBlank() ? null : displayName.trim());
        }
        if (StringUtils.hasText(status)) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!normalized.equals("ENABLED") && !normalized.equals("DISABLED")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户状态仅支持 ENABLED/DISABLED");
            }
            user.setEnabled("ENABLED".equals(normalized) ? 1 : 0);
        }
        if (roles != null) {
            Set<String> safeRoles = new LinkedHashSet<>(roles.stream()
                    .filter(StringUtils::hasText)
                    .map(role -> role.trim().toUpperCase(Locale.ROOT))
                    .toList());
            if (safeRoles.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户至少需要一个角色");
            userMapper.deleteUserRoles(user.getId());
            for (String role : safeRoles) {
                PlatformRoleRecord record = roleMapper.findAll().stream()
                        .filter(item -> item.getCode().equals(role))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色不存在：" + role));
                userMapper.insertUserRole(user.getId(), record.getId(), LocalDateTime.now());
            }
        }
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return toUser(user);
    }

    public List<PlatformRoleRecord> listRoles() {
        return roleMapper.findAll().stream().peek(role ->
                role.setPermissions(permissionMapper.findByRoleName(role.getCode()))).toList();
    }

    @Transactional
    public PlatformRoleRecord createRole(String code, String name, String description, List<String> permissions) {
        String normalizedCode = normalizeRoleCode(StringUtils.hasText(code) ? code : name);
        validatePermissions(permissions);
        try {
            roleMapper.insert(normalizedCode, normalizeDescription(description), LocalDateTime.now());
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "角色已存在：" + normalizedCode);
        }
        PlatformRoleRecord created = roleMapper.findAll().stream()
                .filter(role -> role.getCode().equals(normalizedCode))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "角色创建后查询失败"));
        replacePermissions(created, permissions);
        created.setPermissions(permissions == null ? List.of() : permissions);
        return created;
    }

    @Transactional
    public PlatformRoleRecord updateRole(Long id, String name, String description, List<String> permissions) {
        PlatformRoleRecord role = requireRole(id);
        if ((Boolean.TRUE.equals(role.getBuiltin()) || BUILTIN_ROLES.contains(role.getCode()))
                && name != null && !name.isBlank() && !normalizeRoleCode(name).equals(role.getCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置角色不允许修改编码");
        }
        String code = role.getCode();
        if (StringUtils.hasText(name)) {
            code = normalizeRoleCode(name);
            boolean duplicate = false;
            for (PlatformRoleRecord item : roleMapper.findAll()) {
                if (item.getCode().equals(code)) {
                    duplicate = true;
                    break;
                }
            }
            if (!code.equals(role.getCode()) && duplicate) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "角色已存在：" + code);
            }
        }
        validatePermissions(permissions);
        if (!code.equals(role.getCode())) {
            roleMapper.update(id, code, normalizeDescription(description));
        } else if (description != null) {
            roleMapper.update(id, code, normalizeDescription(description));
        }
        replacePermissions(role, permissions);
        PlatformRoleRecord updated = requireRole(id);
        updated.setPermissions(permissionMapper.findByRoleName(updated.getCode()));
        return updated;
    }

    @Transactional
    public void deleteRole(Long id) {
        PlatformRoleRecord role = requireRole(id);
        if (Boolean.TRUE.equals(role.getBuiltin()) || BUILTIN_ROLES.contains(role.getCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置角色不允许删除");
        }
        if (roleMapper.countUsersByRoleId(id) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "角色仍绑定用户，不能删除");
        }
        roleMapper.deletePermissions(id);
        roleMapper.delete(id);
    }

    private void replacePermissions(PlatformRoleRecord role, List<String> permissions) {
        roleMapper.deletePermissions(role.getId());
        if (permissions == null) return;
        for (String permission : permissions) roleMapper.insertPermissionIfPresent(role.getId(), permission, LocalDateTime.now());
    }

    private void validatePermissions(List<String> permissions) {
        if (permissions == null) return;
        Set<String> existing = new LinkedHashSet<>(permissionMapper.findByAll());
        for (String permission : permissions) {
            if (!existing.contains(permission)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "权限不存在：" + permission);
            }
        }
    }

    private PlatformRoleRecord requireRole(Long id) {
        PlatformRoleRecord role = roleMapper.findById(id);
        if (role == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在");
        return role;
    }

    private PlatformUserRecord toUser(UserAccount user) {
        List<String> roles = userMapper.findRoleNamesByUserId(user.getId());
        return PlatformUserRecord.builder()
                .id(user.getId())
                .username(user.getUsername())
                .displayName(StringUtils.hasText(user.getDisplayName()) ? user.getDisplayName() : user.getUsername())
                .tenantId(user.getTenantId())
                .roles(roles.isEmpty() ? List.of("USER") : roles)
                .status(user.getEnabled() != null && user.getEnabled() == 1 ? "ENABLED" : "DISABLED")
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    private static String normalizeRoleCode(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!ROLE_CODE.matcher(code).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "角色编码需为 2-64 位大写字母、数字或下划线，且以字母开头");
        }
        return code;
    }

    private static String normalizeDescription(String description) {
        return StringUtils.hasText(description) ? description.trim() : null;
    }
}
