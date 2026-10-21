package com.wenjin.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@code spring.task.scheduling.pool.size=2} 时，长阻塞的
 * {@code @Scheduled} 任务不会饿死 1s 周期任务。
 *
 * <p>轻量上下文：仅启用调度 + 排除 DataSource/Flyway，无需 MySQL/Docker。
 * 与生产 {@code application.yml} 中的 pool.size / thread-name-prefix 对齐。
 */
@SpringBootTest(
        classes = SchedulingPoolIT.MinimalSchedulingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.task.scheduling.pool.size=2",
                "spring.task.scheduling.thread-name-prefix=wj-sched-",
                "spring.main.banner-mode=off"
        })
class SchedulingPoolIT {

    /** 观察窗口：略大于 3 个 1s 周期，且短于阻塞桩的 5s。 */
    private static final long OBSERVE_MS = 3_500L;

    @Autowired
    private PeriodicProbe periodicProbe;

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @Test
    @DisplayName("pool.size=2：5s 阻塞 @Scheduled 不饿死 1s 周期任务（窗口内 ≥3 次）")
    void longRunningScheduledTaskDoesNotStarvePeriodicTask() throws InterruptedException {
        assertThat(taskScheduler.getPoolSize()).isEqualTo(2);
        assertThat(taskScheduler.getThreadNamePrefix()).isEqualTo("wj-sched-");

        Thread.sleep(OBSERVE_MS);

        assertThat(periodicProbe.getInvocations())
                .as("1s fixedRate probe should fire >=3 times while another scheduled task blocks 5s")
                .isGreaterThanOrEqualTo(3);
    }

    /**
     * 必须是普通 {@code @Configuration}：
     * <ul>
     *   <li>不能是 {@code @SpringBootApplication}——同包其他 {@code @SpringBootTest} 向上查找
     *       {@code @SpringBootConfiguration} 时会误用它启动（缺 DataSource / 业务 Bean），见 IntegrationConfigDiscoveryTest；</li>
     *   <li>也不能是 {@code @TestConfiguration}——{@code classes} 全是测试配置时 Spring Boot 会额外加载正式启动类，
     *       本用例就不再是轻量上下文。</li>
     * </ul>
     * 作为测试类的内嵌类，它会被 TestTypeExcludeFilter 排除在其他 IT 的组件扫描之外。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            SqlInitializationAutoConfiguration.class
    })
    @EnableScheduling
    static class MinimalSchedulingApp {

        @Bean
        BlockingHog blockingHog() {
            return new BlockingHog();
        }

        @Bean
        PeriodicProbe periodicProbe() {
            return new PeriodicProbe();
        }
    }

    /** 占用调度线程约 5s 的桩任务（模拟 AiJobWorker 内联长任务）。 */
    static class BlockingHog {

        @Scheduled(initialDelay = 0, fixedDelay = 60_000)
        public void blockFiveSeconds() throws InterruptedException {
            Thread.sleep(5_000L);
        }
    }

    /** 1s 周期探针：用于断言调度池在阻塞期间仍可调度其他任务。 */
    static class PeriodicProbe {

        private final AtomicInteger invocations = new AtomicInteger();

        @Scheduled(initialDelay = 0, fixedRate = 1_000)
        public void tick() {
            invocations.incrementAndGet();
        }

        int getInvocations() {
            return invocations.get();
        }
    }
}
