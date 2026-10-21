package com.wenjin.config;

import com.wenjin.audit.AuditInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebConfig CORS：开发默认 *，生产从 wenjin.cors.allowed-origin-patterns 读取。
 */
@ExtendWith(MockitoExtension.class)
class WebConfigTest {

    @Mock
    private TeacherAuthInterceptor teacherAuthInterceptor;

    @Mock
    private AuthContextInterceptor authContextInterceptor;

    @Mock
    private AdminAuthInterceptor adminAuthInterceptor;

    @Mock
    private AuditInterceptor auditInterceptor;

    @Mock
    private CorsRegistry corsRegistry;

    @Mock
    private CorsRegistration corsRegistration;

    private WebConfig webConfig;

    @BeforeEach
    void setUp() {
        webConfig = new WebConfig(teacherAuthInterceptor, authContextInterceptor, adminAuthInterceptor,
                auditInterceptor);
        lenient().when(corsRegistry.addMapping("/api/**")).thenReturn(corsRegistration);
        lenient().when(corsRegistration.allowedOriginPatterns(any(String[].class))).thenReturn(corsRegistration);
        lenient().when(corsRegistration.allowedMethods(any(String[].class))).thenReturn(corsRegistration);
        lenient().when(corsRegistration.allowedHeaders(any(String.class))).thenReturn(corsRegistration);
        lenient().when(corsRegistration.allowCredentials(anyBoolean())).thenReturn(corsRegistration);
    }

    @Test
    void devDefault_allowsWildcardOrigin() {
        ReflectionTestUtils.setField(webConfig, "corsAllowedOriginPatterns", "*");

        webConfig.addCorsMappings(corsRegistry);

        ArgumentCaptor<String[]> originsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(corsRegistration).allowedOriginPatterns(originsCaptor.capture());
        assertThat(originsCaptor.getValue()).containsExactly("*");
        verify(corsRegistration).allowCredentials(false);
    }

    @Test
    void prodConfig_usesConfiguredOriginPattern() {
        ReflectionTestUtils.setField(webConfig, "corsAllowedOriginPatterns", "https://example.com");

        webConfig.addCorsMappings(corsRegistry);

        ArgumentCaptor<String[]> originsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(corsRegistration).allowedOriginPatterns(originsCaptor.capture());
        assertThat(originsCaptor.getValue()).containsExactly("https://example.com");
        verify(corsRegistration).allowCredentials(false);
    }

    @Test
    void multipleOrigins_splitsTrimsAndRegistersEach() {
        ReflectionTestUtils.setField(webConfig, "corsAllowedOriginPatterns",
                "https://a.com, https://b.com");

        webConfig.addCorsMappings(corsRegistry);

        ArgumentCaptor<String[]> originsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(corsRegistration).allowedOriginPatterns(originsCaptor.capture());
        assertThat(originsCaptor.getValue()).containsExactly("https://a.com", "https://b.com");
    }

    @Test
    void parseOriginPatterns_blankFallsBackToWildcard() {
        assertThat(WebConfig.parseOriginPatterns("  ")).containsExactly("*");
        assertThat(WebConfig.parseOriginPatterns("https://x.com,")).containsExactly("https://x.com");
    }

    /** 暴露受保护的 getInterceptors()，按真实排序结果断言注册顺序。 */
    private static final class ExposedRegistry extends InterceptorRegistry {
        List<Object> registered() {
            return getInterceptors();
        }
    }

    private static Object unwrap(Object interceptor) {
        return interceptor instanceof MappedInterceptor mapped ? mapped.getInterceptor() : interceptor;
    }

    @Test
    void interceptorOrder_auditSitsBetweenAuthContextAndRoleGuards() {
        ExposedRegistry registry = new ExposedRegistry();

        webConfig.addInterceptors(registry);

        // afterCompletion 逆序执行：audit 在 authContext 之后注册，才能赶在 CurrentUser 被清理前读到操作人；
        // 在角色拦截器之前注册，401/403 拒绝也能留痕
        assertThat(registry.registered().stream().map(WebConfigTest::unwrap).toList())
                .containsExactly(authContextInterceptor, auditInterceptor,
                        teacherAuthInterceptor, adminAuthInterceptor);
    }
}
