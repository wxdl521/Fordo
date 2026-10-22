package com.wenjin.service.code;

import com.wenjin.config.CodeCheckProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/**
 * 按 {@code wenjin.code-check.backend} 选择沙箱后端（启动时决定）。构造时不调用 docker / 网络。
 */
@Configuration
public class CodeSandboxConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CodeSandboxConfiguration.class);

    @Bean
    public ProcessRunner codeSandboxProcessRunner() {
        return new DefaultProcessRunner();
    }

    @Bean
    public CodeSandbox codeSandbox(CodeCheckProperties props, ProcessRunner codeSandboxProcessRunner, Clock clock) {
        CodeCheckProperties.Backend backend = props.getBackend() == null
                ? CodeCheckProperties.Backend.NONE : props.getBackend();
        CodeSandbox sandbox = switch (backend) {
            case DOCKER -> new DockerCodeSandbox(props.getDocker(), codeSandboxProcessRunner, clock);
            case JUDGE0 -> {
                CodeCheckProperties.Judge0 j = props.getJudge0();
                RestClient client = RestClient.builder().requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(Duration.ofSeconds(5))
                                .withReadTimeout(Duration.ofSeconds(Math.max(5, j.getRequestTimeoutSeconds())))))
                        .build();
                yield new Judge0CodeSandbox(j, client, clock);
            }
            case NONE -> new NoneCodeSandbox();
        };
        log.info("代码执行沙箱后端：{}", sandbox.name());
        return sandbox;
    }
}
