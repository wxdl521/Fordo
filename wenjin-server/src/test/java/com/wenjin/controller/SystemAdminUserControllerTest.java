package com.wenjin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AdminAuthInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.ChangeUserRoleRequest;
import com.wenjin.dto.UserVO;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SystemAdminUserController 角色变更规则单测 + 路由拦截器拒绝非管理员。
 */
@ExtendWith(MockitoExtension.class)
class SystemAdminUserControllerTest {

    @Mock
    private SysUserMapper userMapper;

    private SystemAdminUserController controller;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        controller = new SystemAdminUserController(userMapper);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new AdminAuthInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("PATCH role → TEACHER：管理员成功变更")
    void changeRole_toTeacher_succeedsForAdmin() {
        SysUser target = activeUser(10L, UserRole.STUDENT);
        when(userMapper.selectByIdForUpdate(10L)).thenReturn(target);
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        ChangeUserRoleRequest req = new ChangeUserRoleRequest();
        req.setRole(UserRole.TEACHER.code());

        var result = controller.changeRole(10L, req);

        assertThat(result.getCode()).isEqualTo(0);
        UserVO vo = result.getData();
        assertThat(vo.getId()).isEqualTo(10L);
        assertThat(vo.getRole()).isEqualTo(UserRole.TEACHER.code());

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.TEACHER.code());
    }

    @Test
    @DisplayName("PATCH role → STUDENT：管理员成功变更")
    void changeRole_toStudent_succeedsForAdmin() {
        SysUser target = activeUser(11L, UserRole.TEACHER);
        when(userMapper.selectByIdForUpdate(11L)).thenReturn(target);
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        ChangeUserRoleRequest req = new ChangeUserRoleRequest();
        req.setRole(UserRole.STUDENT.code());

        var result = controller.changeRole(11L, req);

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getData().getRole()).isEqualTo(UserRole.STUDENT.code());
        verify(userMapper).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("无效目标角色（含 ADMIN）→ 400")
    void changeRole_invalidTargetRole_rejected() {
        ChangeUserRoleRequest toAdmin = new ChangeUserRoleRequest();
        toAdmin.setRole(UserRole.ADMIN.code());
        assertThatThrownBy(() -> controller.changeRole(10L, toAdmin))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
                    assertThat(ex.getMessage()).contains("目标角色仅允许");
                });
        verify(userMapper, never()).selectByIdForUpdate(any());

        ChangeUserRoleRequest unknown = new ChangeUserRoleRequest();
        unknown.setRole(99);
        assertThatThrownBy(() -> controller.changeRole(10L, unknown))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.BAD_REQUEST.getCode()));

        assertThatThrownBy(() -> controller.changeRole(10L, new ChangeUserRoleRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("禁止降级最后一个启用管理员")
    void changeRole_cannotDemoteLastActiveAdmin() {
        SysUser lastAdmin = activeUser(1L, UserRole.ADMIN);
        when(userMapper.selectByIdForUpdate(1L)).thenReturn(lastAdmin);
        when(userMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(java.util.List.of(lastAdmin));

        ChangeUserRoleRequest req = new ChangeUserRoleRequest();
        req.setRole(UserRole.TEACHER.code());

        assertThatThrownBy(() -> controller.changeRole(1L, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
                    assertThat(ex.getMessage()).contains("最后一个启用管理员");
                });
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("多个启用管理员时允许降级其中一个")
    void changeRole_canDemoteAdminWhenOthersRemain() {
        SysUser admin = activeUser(1L, UserRole.ADMIN);
        SysUser otherAdmin = activeUser(2L, UserRole.ADMIN);
        when(userMapper.selectByIdForUpdate(1L)).thenReturn(admin);
        when(userMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(java.util.List.of(admin, otherAdmin));
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);

        ChangeUserRoleRequest req = new ChangeUserRoleRequest();
        req.setRole(UserRole.STUDENT.code());

        var result = controller.changeRole(1L, req);
        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getData().getRole()).isEqualTo(UserRole.STUDENT.code());
        verify(userMapper).updateById(any(SysUser.class));
        verify(userMapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("禁止将禁用用户提升为教师")
    void changeRole_cannotPromoteDisabledUserToTeacher() {
        SysUser disabled = activeUser(20L, UserRole.STUDENT);
        disabled.setStatus(0);
        when(userMapper.selectByIdForUpdate(20L)).thenReturn(disabled);

        ChangeUserRoleRequest req = new ChangeUserRoleRequest();
        req.setRole(UserRole.TEACHER.code());

        assertThatThrownBy(() -> controller.changeRole(20L, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
                    assertThat(ex.getMessage()).contains("禁用用户");
                });
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("教师无法访问 system-admin 角色变更（拦截器 403）")
    void teacher_cannotAccessSystemAdmin() throws Exception {
        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
        mvc.perform(patch("/api/system-admin/users/10/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
        verify(userMapper, never()).selectByIdForUpdate(any());
    }

    @Test
    @DisplayName("学生无法访问 system-admin 角色变更（拦截器 403）")
    void student_cannotAccessSystemAdmin() throws Exception {
        CurrentUser.set(new CurrentPrincipal(10L, UserRole.STUDENT));
        mvc.perform(patch("/api/system-admin/users/10/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
        verify(userMapper, never()).selectByIdForUpdate(any());
    }

    private static SysUser activeUser(Long id, UserRole role) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername("user" + id);
        u.setRealName("User " + id);
        u.setRole(role.code());
        u.setStatus(1);
        return u;
    }
}
