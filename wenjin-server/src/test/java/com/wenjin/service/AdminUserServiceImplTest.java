package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.AdminCreateUserRequest;
import com.wenjin.dto.AdminResetPasswordVO;
import com.wenjin.dto.AdminUpdateUserRequest;
import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.AdminUserVO;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.impl.AdminUserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminUserServiceImpl：列表分页 / 创建 / 改名 / 启停 / 重置密码 / 批量插入。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceImplTest {

    private static final long ACTOR_ID = 99L;

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;

    /** 固定时钟：2026-10-21 12:00:00.789123（带亚毫秒，用来验证水位线被截断到毫秒）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 10, 21, 12, 0, 0, 789_123_000).atZone(ZONE).toInstant(), ZONE);

    private AdminUserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminUserServiceImpl(userMapper, passwordEncoder, CLOCK);
        CurrentUser.set(new CurrentPrincipal(ACTOR_ID, UserRole.ADMIN));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    // ───────────────────────── list ─────────────────────────

    @Test
    @DisplayName("list：size 超上限被钳到 100、page<1 取 1，按 id 倒序 LIMIT 0,100，VO 不含密码且 createdAt 为 ISO 串")
    void list_clampsAndMapsRecords() {
        SysUser u = user(5L, "alice", UserRole.STUDENT, 1);
        u.setPassword("$2a$secret");
        u.setCreatedAt(LocalDateTime.of(2026, 10, 21, 10, 15, 30));
        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(u));

        AdminPageVO<AdminUserVO> page = service.list(null, null, null, 0, 500);

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(100);
        assertThat(page.getRecords()).hasSize(1);
        AdminUserVO vo = page.getRecords().get(0);
        assertThat(vo.getId()).isEqualTo(5L);
        assertThat(vo.getUsername()).isEqualTo("alice");
        assertThat(vo.getRole()).isEqualTo(2);
        assertThat(vo.getStatus()).isEqualTo(1);
        assertThat(vo.getCreatedAt()).isEqualTo("2026-10-21T10:15:30");

        ArgumentCaptor<QueryWrapper<SysUser>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(userMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("ORDER BY id DESC").contains("LIMIT 0,100");
    }

    @Test
    @DisplayName("list：默认 page=1,size=20；第 3 页偏移 40")
    void list_defaultsAndOffset() {
        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(100L);
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        AdminPageVO<AdminUserVO> defaults = service.list(null, null, null, null, null);
        assertThat(defaults.getPage()).isEqualTo(1);
        assertThat(defaults.getSize()).isEqualTo(20);

        AdminPageVO<AdminUserVO> third = service.list(null, null, null, 3, 20);
        assertThat(third.getPage()).isEqualTo(3);

        ArgumentCaptor<QueryWrapper<SysUser>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(userMapper, times(2)).selectList(captor.capture());
        assertThat(captor.getAllValues().get(0).getSqlSegment()).contains("LIMIT 0,20");
        assertThat(captor.getAllValues().get(1).getSqlSegment()).contains("LIMIT 40,20");
    }

    @Test
    @DisplayName("list：keyword 去空白并转义 LIKE 通配符；role/status 进入条件")
    void list_keywordEscapedAndFiltersApplied() {
        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        service.list("  a_b%c  ", 1, 0, 1, 20);

        ArgumentCaptor<QueryWrapper<SysUser>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(userMapper).selectCount(captor.capture());
        QueryWrapper<SysUser> w = captor.getValue();
        String sql = w.getSqlSegment();
        assertThat(sql).contains("username").contains("real_name").contains("role").contains("status");
        assertThat(w.getParamNameValuePairs().values())
                .contains("%a\\_b\\%c%")
                .contains(1)
                .contains(0);
    }

    @Test
    @DisplayName("list：总数为 0 时不再查记录")
    void list_zeroTotal_skipsRecordQuery() {
        when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        AdminPageVO<AdminUserVO> page = service.list("nobody", null, null, 1, 20);

        assertThat(page.getTotal()).isZero();
        assertThat(page.getRecords()).isEmpty();
        verify(userMapper, never()).selectList(any(QueryWrapper.class));
    }

    @Test
    @DisplayName("list：非法 role / status 筛选值 → 400")
    void list_invalidFilters_rejected() {
        assertBadRequest(() -> service.list(null, 4, null, 1, 20));
        assertBadRequest(() -> service.list(null, null, 2, 1, 20));
        verify(userMapper, never()).selectCount(any(QueryWrapper.class));
    }

    // ───────────────────────── create ─────────────────────────

    @Test
    @DisplayName("create：成功——用户名/姓名 trim、BCrypt 编码、启用状态，返回库内 createdAt")
    void create_success() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode("Passw0rd!")).thenReturn("HASH");
        when(userMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            ((SysUser) inv.getArgument(0)).setId(7L);
            return 1;
        });
        SysUser saved = user(7L, "newteacher", UserRole.TEACHER, 1);
        saved.setRealName("王老师");
        saved.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        when(userMapper.selectById(7L)).thenReturn(saved);

        AdminUserVO vo = service.create(createReq("  newteacher  ", "Passw0rd!", " 王老师 ", 1));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        SysUser inserted = captor.getValue();
        assertThat(inserted.getUsername()).isEqualTo("newteacher");
        assertThat(inserted.getRealName()).isEqualTo("王老师");
        assertThat(inserted.getPassword()).isEqualTo("HASH");
        assertThat(inserted.getRole()).isEqualTo(UserRole.TEACHER.code());
        assertThat(inserted.getStatus()).isEqualTo(1);
        assertThat(vo.getId()).isEqualTo(7L);
        assertThat(vo.getUsername()).isEqualTo("newteacher");
        assertThat(vo.getRole()).isEqualTo(1);
        assertThat(vo.getCreatedAt()).isEqualTo("2026-01-02T03:04:05");
    }

    @Test
    @DisplayName("create：realName 为空串时存 null")
    void create_blankRealName_storedAsNull() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(userMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            ((SysUser) inv.getArgument(0)).setId(8L);
            return 1;
        });
        when(userMapper.selectById(8L)).thenReturn(null);

        AdminUserVO vo = service.create(createReq("student01", "Passw0rd!", "   ", 2));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getRealName()).isNull();
        assertThat(vo.getId()).isEqualTo(8L);
        assertThat(vo.getRole()).isEqualTo(2);
    }

    @Test
    @DisplayName("create：用户名已存在 → USER_EXISTS(409)，不插入")
    void create_duplicateUsername_rejected() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> service.create(createReq("taken", "Passw0rd!", "x", 2)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(ResultCode.USER_EXISTS.getCode());
                    assertThat(ex.getMessage()).contains("用户名已存在");
                });
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    @DisplayName("create：并发重名触发 DuplicateKeyException → 同样映射为 USER_EXISTS")
    void create_duplicateKeyOnInsert_mapped() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(userMapper.insert(any(SysUser.class))).thenThrow(new DuplicateKeyException("uk_username"));

        assertThatThrownBy(() -> service.create(createReq("racer01", "Passw0rd!", "x", 2)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.USER_EXISTS.getCode()));
    }

    @Test
    @DisplayName("create：角色非法（管理员/未知/空）→ 400，不查库不插入")
    void create_invalidRole_rejected() {
        assertBadRequest(() -> service.create(createReq("someone1", "Passw0rd!", "x", 3)));
        assertBadRequest(() -> service.create(createReq("someone1", "Passw0rd!", "x", 99)));
        assertBadRequest(() -> service.create(createReq("someone1", "Passw0rd!", "x", null)));
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    @DisplayName("create：用户名 trim 后过短 / 密码过短 / 密码超 72 字节 / 姓名过长 → 400")
    void create_fieldRules_rejected() {
        assertBadRequest(() -> service.create(createReq("  ab  ", "Passw0rd!", "x", 2)));
        assertBadRequest(() -> service.create(createReq("a".repeat(65), "Passw0rd!", "x", 2)));
        assertBadRequest(() -> service.create(createReq("someone1", "short", "x", 2)));
        assertBadRequest(() -> service.create(createReq("someone1", "a".repeat(73), "x", 2)));
        // 30 个汉字 = 30 字符但 90 字节，超过 BCrypt 的 72 字节上限
        assertBadRequest(() -> service.create(createReq("someone1", "密".repeat(30), "x", 2)));
        assertBadRequest(() -> service.create(createReq("someone1", "Passw0rd!", "n".repeat(65), 2)));
        assertBadRequest(() -> service.create(createReq(null, "Passw0rd!", "x", 2)));
        verify(userMapper, never()).insert(any(SysUser.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    // ───────────────────────── updateRealName ─────────────────────────

    @Test
    @DisplayName("updateRealName：只更新姓名（trim），返回新姓名")
    void updateRealName_success() {
        SysUser existing = user(10L, "bob", UserRole.STUDENT, 1);
        when(userMapper.selectById(10L)).thenReturn(existing);
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);
        AdminUpdateUserRequest req = new AdminUpdateUserRequest();
        req.setRealName("  鲍勃  ");

        AdminUserVO vo = service.updateRealName(10L, req);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(10L);
        assertThat(captor.getValue().getRealName()).isEqualTo("鲍勃");
        assertThat(captor.getValue().getPassword()).isNull();
        assertThat(captor.getValue().getRole()).isNull();
        assertThat(vo.getRealName()).isEqualTo("鲍勃");
    }

    @Test
    @DisplayName("updateRealName：用户不存在 → 404；姓名空白/过长 → 400")
    void updateRealName_rejections() {
        when(userMapper.selectById(404L)).thenReturn(null);
        AdminUpdateUserRequest ok = new AdminUpdateUserRequest();
        ok.setRealName("某人");
        assertThatThrownBy(() -> service.updateRealName(404L, ok))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.NOT_FOUND.getCode()));

        AdminUpdateUserRequest blank = new AdminUpdateUserRequest();
        blank.setRealName("   ");
        assertBadRequest(() -> service.updateRealName(10L, blank));
        AdminUpdateUserRequest tooLong = new AdminUpdateUserRequest();
        tooLong.setRealName("n".repeat(65));
        assertBadRequest(() -> service.updateRealName(10L, tooLong));
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    // ───────────────────────── changeStatus ─────────────────────────

    @Test
    @DisplayName("changeStatus：禁用教师成功，只写 status")
    void changeStatus_disableTeacher() {
        SysUser teacher = user(20L, "t1", UserRole.TEACHER, 1);
        when(userMapper.selectByIdForUpdate(20L)).thenReturn(teacher);
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        AdminUserVO vo = service.changeStatus(20L, 0);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(20L);
        assertThat(captor.getValue().getStatus()).isEqualTo(0);
        assertThat(captor.getValue().getPassword()).isNull();
        assertThat(vo.getStatus()).isEqualTo(0);
        // 非管理员不需要锁管理员行
        verify(userMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("changeStatus：启用被禁用户")
    void changeStatus_enable() {
        SysUser disabled = user(21L, "s1", UserRole.STUDENT, 0);
        when(userMapper.selectByIdForUpdate(21L)).thenReturn(disabled);
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        AdminUserVO vo = service.changeStatus(21L, 1);

        assertThat(vo.getStatus()).isEqualTo(1);
        verify(userMapper).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("changeStatus：状态未变化时不写库")
    void changeStatus_noop() {
        SysUser active = user(22L, "s2", UserRole.STUDENT, 1);
        when(userMapper.selectByIdForUpdate(22L)).thenReturn(active);

        AdminUserVO vo = service.changeStatus(22L, 1);

        assertThat(vo.getStatus()).isEqualTo(1);
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("changeStatus：不能禁用自己（不查库、不写库）")
    void changeStatus_cannotDisableSelf() {
        assertThatThrownBy(() -> service.changeStatus(ACTOR_ID, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
                    assertThat(ex.getMessage()).contains("不能禁用自己");
                });
        verify(userMapper, never()).selectByIdForUpdate(any());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("changeStatus：禁止禁用最后一个启用管理员")
    void changeStatus_cannotDisableLastActiveAdmin() {
        SysUser lastAdmin = user(1L, "root", UserRole.ADMIN, 1);
        when(userMapper.selectByIdForUpdate(1L)).thenReturn(lastAdmin);
        when(userMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(lastAdmin));

        assertThatThrownBy(() -> service.changeStatus(1L, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
                    assertThat(ex.getMessage()).contains("最后一个启用管理员");
                });
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("changeStatus：仍有其他启用管理员时可以禁用某个管理员")
    void changeStatus_canDisableAdminWhenOthersRemain() {
        SysUser admin = user(1L, "root", UserRole.ADMIN, 1);
        SysUser other = user(2L, "root2", UserRole.ADMIN, 1);
        when(userMapper.selectByIdForUpdate(1L)).thenReturn(admin);
        when(userMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(admin, other));
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        AdminUserVO vo = service.changeStatus(1L, 0);

        assertThat(vo.getStatus()).isEqualTo(0);
        verify(userMapper).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("changeStatus：用户不存在 → 404；status 非 0/1 → 400；未登录 → 401")
    void changeStatus_rejections() {
        when(userMapper.selectByIdForUpdate(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.changeStatus(404L, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.NOT_FOUND.getCode()));

        assertBadRequest(() -> service.changeStatus(20L, 2));
        assertBadRequest(() -> service.changeStatus(20L, null));

        CurrentUser.clear();
        assertThatThrownBy(() -> service.changeStatus(20L, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.UNAUTHORIZED.getCode()));
    }

    // ───────────────────────── resetPassword ─────────────────────────

    @Test
    @DisplayName("resetPassword：不带新密码 → 生成 12 位随机密码、编码落库、仅在返回值里给出明文")
    void resetPassword_generated() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "u30", UserRole.STUDENT, 1));
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        AdminResetPasswordVO vo = service.resetPassword(30L, null);

        assertThat(vo.getPassword()).matches("[A-Za-z0-9]{12}");
        assertThat(vo.getPassword()).matches(".*[a-z].*").matches(".*[A-Z].*").matches(".*[0-9].*");
        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(rawCaptor.capture());
        assertThat(rawCaptor.getValue()).isEqualTo(vo.getPassword());
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(30L);
        assertThat(captor.getValue().getPassword()).isEqualTo("HASH");
        assertThat(captor.getValue().getStatus()).isNull();
    }

    @Test
    @DisplayName("resetPassword：同时写入令牌吊销水位线 token_valid_after = 当前时间截断到毫秒（旧令牌随之失效）")
    void resetPassword_stampsTokenValidAfterTruncatedToMillis() {
        when(userMapper.selectById(34L)).thenReturn(user(34L, "u34", UserRole.STUDENT, 1));
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        service.resetPassword(34L, "NewPass#123");

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTokenValidAfter())
                .isEqualTo(LocalDateTime.of(2026, 10, 21, 12, 0, 0, 789_000_000));
    }

    @Test
    @DisplayName("resetPassword：校验失败/用户不存在时不写水位线（不吊销任何令牌）")
    void resetPassword_rejected_doesNotRevoke() {
        assertBadRequest(() -> service.resetPassword(35L, "short"));
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("resetPassword：空串视为未提供；多次生成互不相同")
    void resetPassword_emptyStringGenerates_andDiffers() {
        when(userMapper.selectById(31L)).thenReturn(user(31L, "u31", UserRole.STUDENT, 1));
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        String first = service.resetPassword(31L, "").getPassword();
        String second = service.resetPassword(31L, null).getPassword();

        assertThat(first).isNotNull().hasSize(12);
        assertThat(second).isNotNull().hasSize(12).isNotEqualTo(first);
    }

    @Test
    @DisplayName("resetPassword：指定新密码 → 编码该密码，返回 password=null")
    void resetPassword_specified() {
        when(userMapper.selectById(32L)).thenReturn(user(32L, "u32", UserRole.TEACHER, 1));
        when(passwordEncoder.encode("NewPass#123")).thenReturn("HASH2");
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        AdminResetPasswordVO vo = service.resetPassword(32L, "NewPass#123");

        assertThat(vo.getPassword()).isNull();
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("HASH2");
    }

    @Test
    @DisplayName("resetPassword：指定密码不合规 → 400；用户不存在 → 404；允许重置自己")
    void resetPassword_rejectionsAndSelf() {
        assertBadRequest(() -> service.resetPassword(33L, "short"));
        verify(userMapper, never()).updateById(any(SysUser.class));

        when(userMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.resetPassword(404L, "NewPass#123"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.NOT_FOUND.getCode()));

        when(userMapper.selectById(ACTOR_ID)).thenReturn(user(ACTOR_ID, "me", UserRole.ADMIN, 1));
        when(passwordEncoder.encode("NewPass#123")).thenReturn("HASH3");
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);
        assertThat(service.resetPassword(ACTOR_ID, "NewPass#123").getPassword()).isNull();
    }

    // ───────────────────────── insertAll ─────────────────────────

    @Test
    @DisplayName("insertAll：逐条插入；唯一键冲突 → USER_EXISTS（事务整体回滚由调用边界保证）")
    void insertAll_insertsAndMapsDuplicate() {
        when(userMapper.insert(any(SysUser.class))).thenReturn(1);
        service.insertAll(List.of(user(null, "a1a", UserRole.STUDENT, 1), user(null, "b2b", UserRole.TEACHER, 1)));
        verify(userMapper, times(2)).insert(any(SysUser.class));

        when(userMapper.insert(any(SysUser.class))).thenThrow(new DuplicateKeyException("uk_username"));
        assertThatThrownBy(() -> service.insertAll(List.of(user(null, "c3c", UserRole.STUDENT, 1))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.USER_EXISTS.getCode()));
    }

    // ───────────────────────── helpers ─────────────────────────

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.BAD_REQUEST.getCode()));
    }

    private static AdminCreateUserRequest createReq(String username, String password, String realName, Integer role) {
        AdminCreateUserRequest req = new AdminCreateUserRequest();
        req.setUsername(username);
        req.setPassword(password);
        req.setRealName(realName);
        req.setRole(role);
        return req;
    }

    private static SysUser user(Long id, String username, UserRole role, int status) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        u.setRealName("User " + username);
        u.setRole(role.code());
        u.setStatus(status);
        return u;
    }
}
