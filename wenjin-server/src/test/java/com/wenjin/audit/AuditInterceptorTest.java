package com.wenjin.audit;

import com.wenjin.config.CurrentUser;
import com.wenjin.config.RequestIdFilter;
import com.wenjin.entity.AuditLog;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.AuditLogMapper;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AuditInterceptor：只记带 {@link Audited} 的控制器方法；写库失败绝不影响业务请求。
 */
@ExtendWith(MockitoExtension.class)
class AuditInterceptorTest {

    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private SysUserMapper userMapper;

    private AuditInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    /** 仅用来拿到真实的 HandlerMethod。 */
    static class Dummy {
        @Audited(action = "USER_STATUS", target = "USER")
        public void audited() {}

        @Audited(action = "SOMETHING")
        public void auditedWithoutTarget() {}

        public void plain() {}
    }

    private static HandlerMethod handler(String method) throws Exception {
        return new HandlerMethod(new Dummy(), Dummy.class.getMethod(method));
    }

    @BeforeEach
    void setUp() {
        interceptor = new AuditInterceptor(auditLogMapper, userMapper);
        request = new MockHttpServletRequest("PATCH", "/api/system-admin/users/9/status");
        request.setRemoteAddr("10.0.0.7");
        request.setAttribute(RequestIdFilter.ATTR, "req-abc");
        response = new MockHttpServletResponse();
        CurrentUser.set(new CurrentPrincipal(1L, UserRole.ADMIN));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    private AuditLog capturedRecord() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static SysUser user(long id, String username) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    @Test
    @DisplayName("带注解 + 成功：写一条完整记录（操作人、动作、对象、请求、状态、IP、requestId）")
    void audited_success_writesFullRecord() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "root"));
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "9"));
        response.setStatus(200);

        interceptor.afterCompletion(request, response, handler("audited"), null);

        AuditLog rec = capturedRecord();
        assertThat(rec.getOperatorId()).isEqualTo(1L);
        assertThat(rec.getOperatorName()).isEqualTo("root");
        assertThat(rec.getOperatorRole()).isEqualTo(UserRole.ADMIN.code());
        assertThat(rec.getAction()).isEqualTo("USER_STATUS");
        assertThat(rec.getTargetType()).isEqualTo("USER");
        assertThat(rec.getTargetId()).isEqualTo("9");
        assertThat(rec.getHttpMethod()).isEqualTo("PATCH");
        assertThat(rec.getPath()).isEqualTo("/api/system-admin/users/9/status");
        assertThat(rec.getHttpStatus()).isEqualTo(200);
        assertThat(rec.getSuccess()).isEqualTo(1);
        assertThat(rec.getIp()).isEqualTo("10.0.0.7");
        assertThat(rec.getRequestId()).isEqualTo("req-abc");
        assertThat(rec.getCreatedAt()).as("交给数据库 DEFAULT CURRENT_TIMESTAMP").isNull();
    }

    @Test
    @DisplayName("路径只记 URI，不带查询串（避免把参数里的敏感值写进日志）")
    void path_excludesQueryString() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "root"));
        request.setRequestURI("/api/admin/graph/import");
        request.setQueryString("courseCode=ABC&token=secret");

        interceptor.afterCompletion(request, response, handler("audited"), null);

        assertThat(capturedRecord().getPath()).isEqualTo("/api/admin/graph/import");
    }

    @Test
    @DisplayName("target_id 取路径变量，优先级 id、courseId、draftId；都没有则为 null")
    void targetId_priority() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "root"));

        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("draftId", "d-1", "courseId", "5", "id", "9"));
        interceptor.afterCompletion(request, response, handler("audited"), null);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("draftId", "d-1", "courseId", "5"));
        interceptor.afterCompletion(request, response, handler("audited"), null);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("draftId", "d-1"));
        interceptor.afterCompletion(request, response, handler("audited"), null);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("other", "x"));
        interceptor.afterCompletion(request, response, handler("audited"), null);
        request.removeAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        interceptor.afterCompletion(request, response, handler("audited"), null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper, times(5)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditLog::getTargetId)
                .containsExactly("9", "5", "d-1", null, null);
    }

    @Test
    @DisplayName("匿名请求不落审计（防止未登录刷接口把审计表刷爆），也不查用户表")
    void anonymous_notRecorded() throws Exception {
        CurrentUser.clear();
        response.setStatus(401);

        interceptor.afterCompletion(request, response, handler("audited"), null);

        verifyNoInteractions(auditLogMapper);
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("状态码 >=400（含 GlobalExceptionHandler 已处理的异常，此时 ex 为 null）记 success=0")
    void errorStatus_isFailure() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "root"));
        response.setStatus(403);

        interceptor.afterCompletion(request, response, handler("audited"), null);

        AuditLog rec = capturedRecord();
        assertThat(rec.getHttpStatus()).isEqualTo(403);
        assertThat(rec.getSuccess()).isZero();
    }

    @Test
    @DisplayName("未被处理的异常（ex != null）即使状态码仍是 200 也记失败，状态按 500 记")
    void unhandledException_isFailure() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "root"));
        response.setStatus(200);

        interceptor.afterCompletion(request, response, handler("audited"), new IllegalStateException("boom"));

        AuditLog rec = capturedRecord();
        assertThat(rec.getSuccess()).isZero();
        assertThat(rec.getHttpStatus()).isEqualTo(500);
    }

    @Test
    @DisplayName("没有 @Audited 的方法不记；非 HandlerMethod（静态资源等）也不记")
    void notAudited_notRecorded() throws Exception {
        interceptor.afterCompletion(request, response, handler("plain"), null);
        interceptor.afterCompletion(request, response, new Object(), null);

        verify(auditLogMapper, never()).insert(any(AuditLog.class));
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("写库抛异常：吞掉（只打 warn），绝不影响业务请求")
    void insertFailure_isSwallowed() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "root"));
        when(auditLogMapper.insert(any(AuditLog.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> interceptor.afterCompletion(request, response, handler("audited"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("查操作人用户名失败：仍写记录（用户名留空），不抛出")
    void operatorLookupFailure_stillRecords() throws Exception {
        when(userMapper.selectById(1L)).thenThrow(new RuntimeException("db hiccup"));

        assertThatCode(() -> interceptor.afterCompletion(request, response, handler("audited"), null))
                .doesNotThrowAnyException();

        AuditLog rec = capturedRecord();
        assertThat(rec.getOperatorId()).isEqualTo(1L);
        assertThat(rec.getOperatorName()).isNull();
    }

    @Test
    @DisplayName("IP：优先 X-Forwarded-For 首段，否则 remoteAddr")
    void ip_prefersForwardedFor() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "root"));
        request.addHeader("X-Forwarded-For", " 203.0.113.9 , 10.0.0.1");

        interceptor.afterCompletion(request, response, handler("audited"), null);

        assertThat(capturedRecord().getIp()).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("@Audited 未写 target 则 target_type 为 null；缺 requestId 属性则为 null")
    void blankTargetAndMissingRequestId() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "root"));
        request.removeAttribute(RequestIdFilter.ATTR);

        interceptor.afterCompletion(request, response, handler("auditedWithoutTarget"), null);

        AuditLog rec = capturedRecord();
        assertThat(rec.getAction()).isEqualTo("SOMETHING");
        assertThat(rec.getTargetType()).isNull();
        assertThat(rec.getRequestId()).isNull();
    }

    @Test
    @DisplayName("超长字段按列宽截断（path 255 / target_id 64 / operator_name 64 / ip 64）")
    void longFields_areTruncated() throws Exception {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "n".repeat(100)));
        request.setRequestURI("/api/" + "p".repeat(400));
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "t".repeat(100)));
        request.addHeader("X-Forwarded-For", "9".repeat(100));

        interceptor.afterCompletion(request, response, handler("audited"), null);

        AuditLog rec = capturedRecord();
        assertThat(rec.getPath()).hasSize(255);
        assertThat(rec.getTargetId()).hasSize(64);
        assertThat(rec.getOperatorName()).hasSize(64);
        assertThat(rec.getIp()).hasSize(64);
    }
}
