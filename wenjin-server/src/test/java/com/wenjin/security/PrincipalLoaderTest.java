package com.wenjin.security;

import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * PrincipalLoader：状态/角色回查 + 令牌吊销水位线（token_valid_after）比较。
 */
@ExtendWith(MockitoExtension.class)
class PrincipalLoaderTest {

    private static final LocalDateTime VALID_AFTER = LocalDateTime.of(2026, 10, 21, 12, 0, 0, 500_000_000);

    @Mock
    private SysUserMapper userMapper;

    private PrincipalLoader loader;

    @BeforeEach
    void setUp() {
        loader = new PrincipalLoader(userMapper);
    }

    private static long epoch(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static SysUser user(long id, int role, int status, LocalDateTime tokenValidAfter) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setRole(role);
        u.setStatus(status);
        u.setTokenValidAfter(tokenValidAfter);
        return u;
    }

    @Test
    @DisplayName("无水位线的用户：任意 iat（含旧令牌的 0）都放行")
    void noWatermark_anyIatAccepted() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, UserRole.STUDENT.code(), 1, null));

        assertThat(loader.load(5L, 0L)).map(CurrentPrincipal::userId).contains(5L);
        assertThat(loader.load(5L, 1_700_000_000_000L)).isPresent();
    }

    @Test
    @DisplayName("iat 早于水位线 → 视为无效（空）")
    void iatBeforeWatermark_rejected() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, UserRole.STUDENT.code(), 1, VALID_AFTER));

        assertThat(loader.load(5L, epoch(VALID_AFTER) - 1)).isEmpty();
    }

    @Test
    @DisplayName("旧令牌（iat=0）遇到水位线 → 无效")
    void legacyTokenWithWatermark_rejected() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, UserRole.STUDENT.code(), 1, VALID_AFTER));

        assertThat(loader.load(5L, 0L)).isEmpty();
    }

    @Test
    @DisplayName("iat 等于或晚于水位线（重置后登录拿到的新令牌）→ 有效")
    void iatAtOrAfterWatermark_accepted() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, UserRole.TEACHER.code(), 1, VALID_AFTER));

        Optional<CurrentPrincipal> sameMs = loader.load(5L, epoch(VALID_AFTER));
        assertThat(sameMs).isPresent();
        assertThat(sameMs.get().role()).isEqualTo(UserRole.TEACHER);
        assertThat(loader.load(5L, epoch(VALID_AFTER) + 1)).isPresent();
    }

    @Test
    @DisplayName("与水位线同一秒但更早签发的令牌 → 无效（毫秒精度，无同秒漏洞窗口）")
    void sameSecondButEarlier_rejected() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, UserRole.STUDENT.code(), 1, VALID_AFTER));

        assertThat(loader.load(5L, epoch(VALID_AFTER) - 100)).isEmpty();
    }

    @Test
    @DisplayName("带 iat 的加载仍先做状态/角色/存在性检查")
    void iatLoad_stillChecksStatusAndExistence() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, UserRole.STUDENT.code(), 0, null));
        when(userMapper.selectById(2L)).thenReturn(null);
        when(userMapper.selectById(3L)).thenReturn(user(3L, 99, 1, null));

        assertThat(loader.load(1L, 0L)).isEmpty();
        assertThat(loader.load(2L, 0L)).isEmpty();
        assertThat(loader.load(3L, 0L)).isEmpty();
    }

    @Test
    @DisplayName("不带令牌上下文的 load(uid)（AI 作业 worker 用）不做水位线比较")
    void loadWithoutToken_ignoresWatermark() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, UserRole.TEACHER.code(), 1, VALID_AFTER));

        assertThat(loader.load(5L)).isPresent();
    }
}
