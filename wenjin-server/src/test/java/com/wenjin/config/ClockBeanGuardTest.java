package com.wenjin.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局 {@link Clock} 只能由 {@link ClockConfig} 提供（系统时区）。
 *
 * <p>{@code ClockConfig} 是 {@code @ConditionalOnMissingBean}：任何别的配置类再声明一个 Clock，
 * 都会静默替换全局时钟。曾有 {@code MailConfig} 声明 {@code Clock.systemUTC()}，导致令牌吊销水位线、
 * 遗忘衰减、复习调度、总览按日统计全部按 UTC 计算（与东八区差 8 小时）。</p>
 */
class ClockBeanGuardTest {

    @Test
    @DisplayName("除 ClockConfig 外，任何 @Configuration 都不得声明 Clock bean")
    void onlyClockConfigDeclaresClockBean() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));

        List<String> offenders = new ArrayList<>();
        for (BeanDefinition def : scanner.findCandidateComponents("com.wenjin")) {
            Class<?> type = Class.forName(def.getBeanClassName());
            if (type == ClockConfig.class) {
                continue;
            }
            for (Method m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Bean.class) && Clock.class.isAssignableFrom(m.getReturnType())) {
                    offenders.add(type.getSimpleName() + "#" + m.getName());
                }
            }
        }

        assertThat(offenders).as("这些配置类声明了 Clock bean，会覆盖 ClockConfig 的系统时区时钟").isEmpty();
    }
}
