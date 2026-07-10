package com.wenjin;

import com.wenjin.config.AiProperties;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.FeatureFlagsProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 问津后端服务启动类（第一阶段：图谱导入 / 查询）。
 */
@SpringBootApplication
@MapperScan("com.wenjin.mapper")
@EnableConfigurationProperties({AiProperties.class, CompanionToolProperties.class, FeatureFlagsProperties.class})
public class WenjinServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WenjinServerApplication.class, args);
    }
}
