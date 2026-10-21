package com.wenjin.config;

import com.wenjin.audit.AuditInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置。开发期放开跨域，便于前端直连调试（生产经反向代理时可收紧）。
 * 同时注册拦截器：authContext 先填充 CurrentUser（全部 /api/**），
 * audit 写审计日志（全部 /api/**，仅处理带 {@code @Audited} 的方法），
 * teacherAuth 限教师/管理员（/api/teacher/** 与 /api/admin/**），
 * adminAuth 限系统管理员（/api/system-admin/**）。
 * <p>audit 必须紧跟 authContext 之后、角色拦截器之前，理由见 {@link AuditInterceptor}。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${wenjin.cors.allowed-origin-patterns:*}")
    private String corsAllowedOriginPatterns;

    private final TeacherAuthInterceptor teacherAuthInterceptor;
    private final AuthContextInterceptor authContextInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;
    private final AuditInterceptor auditInterceptor;

    public WebConfig(TeacherAuthInterceptor teacherAuthInterceptor,
                     AuthContextInterceptor authContextInterceptor,
                     AdminAuthInterceptor adminAuthInterceptor,
                     AuditInterceptor auditInterceptor) {
        this.teacherAuthInterceptor = teacherAuthInterceptor;
        this.authContextInterceptor = authContextInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.auditInterceptor = auditInterceptor;
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
        // 2. 审计：afterCompletion 逆序执行，注册在 authContext 之后才能在它清掉 CurrentUser 之前读到操作人；
        //    注册在角色拦截器之前，被它们 401/403 拒绝的请求也会留下失败记录
        registry.addInterceptor(auditInterceptor).addPathPatterns("/api/**");
        // 3. 教师端：TEACHER 或 ADMIN
        registry.addInterceptor(teacherAuthInterceptor)
                .addPathPatterns("/api/teacher/**", "/api/admin/**");
        // 4. 系统管理员端：仅 ADMIN
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/system-admin/**");
    }
}
