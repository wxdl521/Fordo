package com.wenjin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置。开发期放开跨域，便于前端直连调试（生产经反向代理时可收紧）。
 * 同时注册拦截器：authContext 先填充 CurrentUser（全部 /api/**），
 * teacherAuth 再限角色（/api/teacher/** 与 /api/admin/**）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TeacherAuthInterceptor teacherAuthInterceptor;
    private final AuthContextInterceptor authContextInterceptor;

    public WebConfig(TeacherAuthInterceptor teacherAuthInterceptor,
                     AuthContextInterceptor authContextInterceptor) {
        this.teacherAuthInterceptor = teacherAuthInterceptor;
        this.authContextInterceptor = authContextInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 先填充当前用户上下文（全 /api/**，匿名可过，不抛异常）
        registry.addInterceptor(authContextInterceptor).addPathPatterns("/api/**");
        // 2. 再限教师/管理端角色（需 role==1，缺身份或非教师抛 401/403）
        registry.addInterceptor(teacherAuthInterceptor)
                .addPathPatterns("/api/teacher/**", "/api/admin/**");
    }
}
