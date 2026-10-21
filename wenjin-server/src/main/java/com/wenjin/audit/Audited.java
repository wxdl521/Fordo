package com.wenjin.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要写入审计日志（{@code audit_log}）的控制器方法，由 {@link AuditInterceptor} 在请求完成后读取。
 *
 * <p>没有标注的方法不记录。无 AOP：拦截器通过 {@code HandlerMethod} 读取本注解。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {

    /** 动作编码，如 {@code USER_CREATE} / {@code COURSE_DELETE}。 */
    String action();

    /** 对象类型，如 {@code USER / COURSE / GRAPH / QUESTION / RAG}；缺省不记。 */
    String target() default "";
}
