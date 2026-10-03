package com.enterprise.iqk.security;

import com.enterprise.iqk.domain.UserAccount;
import com.enterprise.iqk.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAuthServiceTest {
    @Mock
    private UserAccountMapper userAccountMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @InjectMocks
    private UserAuthService userAuthService;

    @Test
    void shouldNormalizeUsernameToLowerCase() {
        assertEquals("alice", userAuthService.normalizeUsername(" Alice "));
    }

    @Test
    void shouldRejectInvalidUsernames() {
        assertThrows(IllegalArgumentException.class, () -> userAuthService.normalizeUsername(null));
        assertThrows(IllegalArgumentException.class, () -> userAuthService.normalizeUsername("ab"));
        assertThrows(IllegalArgumentException.class, () -> userAuthService.normalizeUsername("Bad Name"));
        assertThrows(IllegalArgumentException.class, () -> userAuthService.normalizeUsername("a".repeat(33)));
    }

    @Test
    void shouldRejectReservedNames() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userAuthService.normalizeUsername("Admin"));
        assertEquals("该用户名为系统保留名，请换一个", ex.getMessage());
    }

    @Test
    void shouldRegisterWithPrivateTenantAndUserRole() {
        when(userAccountMapper.findByUsername("alice")).thenReturn(null);
        when(passwordEncoder.encode("password123")).thenReturn("bcrypt-hash");
        when(userAccountMapper.findRoleIdByName("USER")).thenReturn(2L);

        UserAccount user = userAuthService.register("Alice", "password123");

        assertEquals("alice", user.getUsername());
        assertEquals("u-alice", user.getTenantId());
        assertEquals("bcrypt-hash", user.getPasswordHash());
        assertEquals(1, user.getEnabled());
        verify(userAccountMapper).insert(user);
        ArgumentCaptor<Long> roleId = ArgumentCaptor.forClass(Long.class);
        verify(userAccountMapper).insertUserRole(any(), roleId.capture(), any());
        assertEquals(2L, roleId.getValue());
    }

    @Test
    void shouldRejectDuplicateUsernameFromPreCheck() {
        when(userAccountMapper.findByUsername("alice")).thenReturn(UserAccount.builder().id(9L).build());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userAuthService.register("alice", "password123"));
        assertEquals("用户名已被占用", ex.getMessage());
        verify(userAccountMapper, never()).insert(any(UserAccount.class));
    }

    @Test
    void shouldRejectDuplicateUsernameFromConcurrentInsert() {
        when(userAccountMapper.findByUsername("alice")).thenReturn(null);
        when(passwordEncoder.encode(any())).thenReturn("bcrypt-hash");
        when(userAccountMapper.insert(any(UserAccount.class))).thenThrow(new DuplicateKeyException("dup"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userAuthService.register("alice", "password123"));
        assertEquals("用户名已被占用", ex.getMessage());
    }

    @Test
    void shouldFailClosedWhenUserRoleSeedMissing() {
        when(userAccountMapper.findByUsername("alice")).thenReturn(null);
        when(passwordEncoder.encode(any())).thenReturn("bcrypt-hash");
        when(userAccountMapper.findRoleIdByName("USER")).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> userAuthService.register("alice", "password123"));
    }

    @Test
    void shouldRejectShortAndOverlongPasswords() {
        assertThrows(IllegalArgumentException.class, () -> userAuthService.register("alice", "short"));
        assertThrows(IllegalArgumentException.class, () -> userAuthService.register("alice", "p".repeat(73)));
        verify(userAccountMapper, never()).insert(any(UserAccount.class));
    }

    @Test
    void shouldVerifyCorrectCredentials() {
        UserAccount stored = UserAccount.builder()
                .id(1L)
                .username("alice")
                .passwordHash("bcrypt-hash")
                .enabled(1)
                .build();
        when(userAccountMapper.findByUsername("alice")).thenReturn(stored);
        when(passwordEncoder.matches("password123", "bcrypt-hash")).thenReturn(true);

        assertEquals(stored, userAuthService.verify("Alice", "password123"));
    }

    @Test
    void shouldReturnNullWithoutLeakingReason() {
        // 用户不存在
        when(userAccountMapper.findByUsername("ghost")).thenReturn(null);
        assertNull(userAuthService.verify("ghost", "password123"));

        // 密码错误
        UserAccount stored = UserAccount.builder().id(1L).username("alice").passwordHash("h").enabled(1).build();
        when(userAccountMapper.findByUsername("alice")).thenReturn(stored);
        when(passwordEncoder.matches("wrong-pass", "h")).thenReturn(false);
        assertNull(userAuthService.verify("alice", "wrong-pass"));

        // 已停用
        UserAccount disabled = UserAccount.builder().id(1L).username("bob").passwordHash("h").enabled(0).build();
        when(userAccountMapper.findByUsername("bob")).thenReturn(disabled);
        assertNull(userAuthService.verify("bob", "password123"));

        // 用户名非法：与"不存在"同语义，不抛异常
        assertNull(userAuthService.verify("A B", "password123"));
    }

    @Test
    void shouldDefaultToUserRoleWhenNoRows() {
        UserAccount user = UserAccount.builder().id(1L).username("alice").build();
        when(userAccountMapper.findRoleNamesByUserId(1L)).thenReturn(java.util.List.of());

        assertTrue(userAuthService.roleNamesOf(user).contains("USER"));
    }
}
