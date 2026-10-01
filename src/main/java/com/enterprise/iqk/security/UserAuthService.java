package com.enterprise.iqk.security;

import com.enterprise.iqk.domain.UserAccount;
import com.enterprise.iqk.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 注册用户账号服务：用户名/密码校验、BCrypt 哈希落库、密码登录校验。
 *
 * <p>隔离模型：每个自注册用户独占租户 {@code u-<小写用户名>}，
 * 向量 / 会话 / 花费等既有隔离按租户生效；管理员 bootstrap key 仍在 'public'。</p>
 *
 * <p>防枚举约定：{@link #verify} 对"用户不存在 / 密码错误 / 已停用"
 * 一律返回 null，不向调用方区分原因。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAuthService {

    public static final String DEFAULT_ROLE = "USER";
    /** 租户前缀：'u-' + 用户名（≤32）最长 34，safe for VARCHAR(64)。 */
    private static final String USER_TENANT_PREFIX = "u-";
    /** 用户名规则：3-32 位小写字母/数字/-/_；注册时统一转小写。 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_-]{3,32}$");
    private static final int PASSWORD_MIN_LENGTH = 8;
    /** BCrypt 只取输入前 72 字节，超长部分会被静默截断，因此直接拒绝。 */
    private static final int PASSWORD_MAX_LENGTH = 72;
    /** 高价值保留名，防止抢注后冒充管理员/系统账号。 */
    private static final Set<String> RESERVED_NAMES = Set.of(
            "admin", "administrator", "root", "ops", "system", "public",
            "user", "moderator", "support", "api");

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;

    /** 归一化并校验用户名（统一小写）；非法或保留名抛 IllegalArgumentException。 */
    public String normalizeUsername(String rawUsername) {
        String username = rawUsername == null ? "" : rawUsername.trim().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("username is required");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("username must be 3-32 chars of a-z 0-9 - _");
        }
        if (RESERVED_NAMES.contains(username)) {
            throw new IllegalArgumentException("username is reserved");
        }
        return username;
    }

    /** 注册：BCrypt 存哈希、私有租户 u-&lt;username&gt;、绑定 USER 角色；重名（含并发插入）抛 IllegalArgumentException。 */
    @Transactional
    public UserAccount register(String rawUsername, String rawPassword) {
        String username = normalizeUsername(rawUsername);
        validatePassword(rawPassword);
        if (userAccountMapper.findByUsername(username) != null) {
            throw new IllegalArgumentException("username already taken");
        }
        LocalDateTime now = LocalDateTime.now();
        UserAccount user = UserAccount.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .tenantId(USER_TENANT_PREFIX + username)
                .enabled(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            userAccountMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            // 并发注册同名：唯一索引兜底，转为与预检查一致的语义
            throw new IllegalArgumentException("username already taken");
        }
        Long roleId = userAccountMapper.findRoleIdByName(DEFAULT_ROLE);
        if (roleId != null) {
            userAccountMapper.insertUserRole(user.getId(), roleId, now);
        } else {
            // roles 表缺 USER 种子属环境异常：账号可用但无角色授权，直接失败更安全
            throw new IllegalStateException("role USER is missing; check flyway seed data");
        }
        log.info("user registered: username={}, tenant={}", username, user.getTenantId());
        return user;
    }

    /** 密码校验：不存在 / 密码不符 / enabled=0 一律返回 null（不区分原因，防用户名枚举）。 */
    public UserAccount verify(String rawUsername, String rawPassword) {
        if (!StringUtils.hasText(rawPassword)) {
            return null;
        }
        String username;
        try {
            username = normalizeUsername(rawUsername);
        } catch (IllegalArgumentException ex) {
            // 与"用户不存在"同语义，不泄露校验细节
            return null;
        }
        UserAccount user = userAccountMapper.findByUsername(username);
        if (user == null || user.getEnabled() == null || user.getEnabled() != 1) {
            return null;
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            return null;
        }
        return user;
    }

    /** 读取用户角色名列表（user_roles → roles）；空则默认 USER。 */
    public List<String> roleNamesOf(UserAccount user) {
        List<String> roles = userAccountMapper.findRoleNamesByUserId(user.getId());
        return roles.isEmpty() ? List.of(DEFAULT_ROLE) : roles;
    }

    private void validatePassword(String rawPassword) {
        if (!StringUtils.hasText(rawPassword) || rawPassword.length() < PASSWORD_MIN_LENGTH) {
            throw new IllegalArgumentException("password must be at least " + PASSWORD_MIN_LENGTH + " chars");
        }
        if (rawPassword.length() > PASSWORD_MAX_LENGTH) {
            throw new IllegalArgumentException("password must be at most " + PASSWORD_MAX_LENGTH + " chars");
        }
    }
}
