package com.wenjin.security;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 首个管理员引导：仅无 ADMIN 时创建；缺凭据/弱密码/已存在 ADMIN 均拒绝。
 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock
    private SysUserMapper userMapper;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private AdminBootstrapRunner runner(String username, String password) {
        return new AdminBootstrapRunner(userMapper, encoder, username, password);
    }

    @Test
    void createsAdmin_whenNoneExists() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            ((SysUser) inv.getArgument(0)).setId(100L);
            return 1;
        });

        runner("bootstrap_admin", "sixteen-char-pw!").run(null);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        SysUser created = captor.getValue();
        assertThat(created.getUsername()).isEqualTo("bootstrap_admin");
        assertThat(created.getRole()).isEqualTo(UserRole.ADMIN.code());
        assertThat(created.getStatus()).isEqualTo(1);
        assertThat(created.getPassword()).startsWith("$2");
        assertThat(encoder.matches("sixteen-char-pw!", created.getPassword())).isTrue();
    }

    @Test
    void refuses_whenAdminAlreadyExists() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> runner("bootstrap_admin", "sixteen-char-pw!").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已存在");

        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void refuses_whenUsernameAlreadyTaken() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        SysUser existing = new SysUser();
        existing.setId(7L);
        existing.setUsername("taken_user");
        existing.setRole(UserRole.STUDENT.code());
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        assertThatThrownBy(() -> runner("taken_user", "sixteen-char-pw!").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("用户名已存在")
                .hasMessageContaining("taken_user");

        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void refuses_withClearMessage_whenInsertHitsDuplicateKey() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userMapper.insert(any(SysUser.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_username"));

        assertThatThrownBy(() -> runner("race_user", "sixteen-char-pw!").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("用户名已存在")
                .hasMessageContaining("race_user");
    }

    @Test
    void refuses_whenUsernameBlank() {
        assertThatThrownBy(() -> runner("  ", "sixteen-char-pw!").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username");
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void refuses_whenPasswordTooShort() {
        assertThatThrownBy(() -> runner("bootstrap_admin", "short").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password");
        verify(userMapper, never()).insert(any(SysUser.class));
    }
}
