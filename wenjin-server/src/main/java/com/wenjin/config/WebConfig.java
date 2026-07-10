package com.wenjin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置。开发期放开跨域，便于前端直连调试（生产经反向代理时可收紧）。
 * 同时注册拦截器：authContext 先填充 CurrentUser（全部 /api/**），
 * teacherAuth 限教师/管理员（/api/teacher/** 与 /api/admin/**），
 * adminAuth 限系统管理员（/api/system-admin/**）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${wenjin.cors.allowed-origin-patterns:*}")
    private String corsAllowedOriginPatterns;

    private final TeacherAuthInterceptor teacherAuthInterceptor;
    private final AuthContextInterceptor authContextInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    public WebConfig(TeacherAuthInterceptor teacherAuthInterceptor,
                     AuthContextInterceptor authContextInterceptor,
                     AdminAuthInterceptor adminAuthInterceptor) {
        this.teacherAuthInterceptor = teacherAuthInterceptor;
        this.authContextInterceptor = authContextInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(parseOriginPatterns(corsAllowedOriginPatterns))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }

    static String[] parseOriginPatterns(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[]{"*"};
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 先填充当前用户上下文（全 /api/**，匿名可过，不抛异常）
        registry.addInterceptor(authContextInterceptor).addPathPatterns("/api/**");
        // 2. 教师端：TEACHER 或 ADMIN
        registry.addInterceptor(teacherAuthInterceptor)
                .addPathPatterns("/api/teacher/**", "/api/admin/**");
        // 3. 系统管理员端：仅 ADMIN
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/system-admin/**");
    }
}
