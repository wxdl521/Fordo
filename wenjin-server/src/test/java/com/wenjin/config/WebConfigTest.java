package com.wenjin.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    private CorsRegistry corsRegistry;

    @Mock
    private CorsRegistration corsRegistration;

    private WebConfig webConfig;

    @BeforeEach
    void setUp() {
        webConfig = new WebConfig(teacherAuthInterceptor, authContextInterceptor);
        when(corsRegistry.addMapping("/api/**")).thenReturn(corsRegistration);
        when(corsRegistration.allowedOriginPatterns(any(String[].class))).thenReturn(corsRegistration);
        when(corsRegistration.allowedMethods(any(String[].class))).thenReturn(corsRegistration);
        when(corsRegistration.allowedHeaders(any(String.class))).thenReturn(corsRegistration);
        when(corsRegistration.allowCredentials(anyBoolean())).thenReturn(corsRegistration);
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
}