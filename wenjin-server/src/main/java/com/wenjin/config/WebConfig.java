package com.wenjin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置。开发期放开跨域，便于前端直连调试（生产经反向代理时可收紧）。
 * 同时注册教师端鉴权拦截器，保护 /api/teacher/** 与 /api/admin/**。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TeacherAuthInterceptor teacherAuthInterceptor;

    public WebConfig(TeacherAuthInterceptor teacherAuthInterceptor) {
        this.teacherAuthInterceptor = teacherAuthInterceptor;
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
        // 教师端 / 管理端接口需教师身份；登录注册等其它接口不受影响
        registry.addInterceptor(teacherAuthInterceptor)
                .addPathPatterns("/api/teacher/**", "/api/admin/**");
    }
}
