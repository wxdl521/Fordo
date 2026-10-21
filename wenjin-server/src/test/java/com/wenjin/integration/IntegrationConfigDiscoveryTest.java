package com.wenjin.integration;

import com.wenjin.WenjinServerApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.AnnotatedClassFinder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本包下未显式指定 {@code classes} 的 {@code @SpringBootTest} 会从本包向上查找 {@code @SpringBootConfiguration}，
 * 必须找到正式启动类 {@link WenjinServerApplication}。
 *
 * <p>曾因 {@code SchedulingPoolIT} 内嵌了一个 {@code @SpringBootApplication} 精简配置，
 * 同包 10 个 IT 全部误用它启动（缺 DataSource 与业务 Bean，注入失败）。本测试不需要 Docker，
 * 在单元测试阶段就能拦住这类问题。</p>
 */
class IntegrationConfigDiscoveryTest {

    @Test
    @DisplayName("integration 包的 @SpringBootTest 解析到的启动类是 WenjinServerApplication")
    void integrationTestsResolveToProductionApplication() {
        Class<?> found = new AnnotatedClassFinder(SpringBootConfiguration.class)
                .findFromClass(IntegrationConfigDiscoveryTest.class);

        assertThat(found).isEqualTo(WenjinServerApplication.class);
    }
}
