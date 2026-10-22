package com.wenjin;

import com.wenjin.config.AiProperties;
import com.wenjin.config.CodeCheckProperties;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 问津后端服务启动类（第一阶段：图谱导入 / 查询）。
 */
@SpringBootApplication
@MapperScan("com.wenjin.mapper")
@EnableConfigurationProperties({
        AiProperties.class,
        CodeCheckProperties.class,
        CompanionToolProperties.class,
        FeatureFlagsProperties.class,
        LimitsProperties.class
})
@EnableScheduling
public class WenjinServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WenjinServerApplication.class, args);
    }
}
