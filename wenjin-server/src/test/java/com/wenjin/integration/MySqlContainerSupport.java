package com.wenjin.integration;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 共享 MySQL 8.4 Testcontainers 底座。
 * 集成测试必须连此容器，禁止连接开发者本机 {@code root/root}。
 */
@Testcontainers
abstract class MySqlContainerSupport {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("wenjin")
            .withUsername("wenjin_test")
            .withPassword("wenjin_test");
}
