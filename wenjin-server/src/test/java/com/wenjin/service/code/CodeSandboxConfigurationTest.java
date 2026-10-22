package com.wenjin.service.code;

import com.wenjin.config.CodeCheckProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeSandboxConfigurationTest {

    private final CodeSandboxConfiguration config = new CodeSandboxConfiguration();

    @Test
    @DisplayName("backend 选择：docker / judge0 包一层后台可用性刷新；none 恒不可用且执行 FAILED")
    void backendSelection() {
        CodeCheckProperties props = new CodeCheckProperties();
        props.getDocker().setBinary("definitely-not-docker-wj");
        try (AsyncAvailabilitySandbox docker = (AsyncAvailabilitySandbox) config.codeSandbox(props,
                new DefaultProcessRunner())) {
            assertThat(docker.name()).isEqualTo("docker");
            // 请求路径不阻塞：后台首次探测完成前（或失败后）读到的都是 false
            assertThat(docker.available()).isFalse();
        }

        props.setBackend(CodeCheckProperties.Backend.JUDGE0);
        try (AsyncAvailabilitySandbox judge0 = (AsyncAvailabilitySandbox) config.codeSandbox(props,
                new DefaultProcessRunner())) {
            assertThat(judge0.name()).isEqualTo("judge0");
            assertThat(judge0.available()).isFalse();
        }

        props.setBackend(CodeCheckProperties.Backend.NONE);
        CodeSandbox none = config.codeSandbox(props, new DefaultProcessRunner());
        assertThat(none).isInstanceOf(NoneCodeSandbox.class);
        assertThat(none.available()).isFalse();
        assertThat(none.name()).isEqualTo("none");
        assertThat(none.execute(new SandboxRequest("x", List.of(), DockerRunnerScriptTest.LIMITS)).status())
                .isEqualTo(SandboxResult.Status.FAILED);

        props.setBackend(null);
        assertThat(config.codeSandbox(props, new DefaultProcessRunner()).available()).isFalse();
    }
}
