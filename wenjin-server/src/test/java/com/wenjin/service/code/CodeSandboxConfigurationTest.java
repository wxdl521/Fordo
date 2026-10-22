package com.wenjin.service.code;

import com.wenjin.config.CodeCheckProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class CodeSandboxConfigurationTest {

    private final CodeSandboxConfiguration config = new CodeSandboxConfiguration();

    @Test
    @DisplayName("backend 选择：docker / judge0 / none（none 恒不可用且执行 FAILED）")
    void backendSelection() {
        CodeCheckProperties props = new CodeCheckProperties();
        assertThat(config.codeSandbox(props, new DefaultProcessRunner(), Clock.systemUTC()))
                .isInstanceOf(DockerCodeSandbox.class);

        props.setBackend(CodeCheckProperties.Backend.JUDGE0);
        assertThat(config.codeSandbox(props, new DefaultProcessRunner(), Clock.systemUTC()))
                .isInstanceOf(Judge0CodeSandbox.class);

        props.setBackend(CodeCheckProperties.Backend.NONE);
        CodeSandbox none = config.codeSandbox(props, new DefaultProcessRunner(), Clock.systemUTC());
        assertThat(none.available()).isFalse();
        assertThat(none.name()).isEqualTo("none");
        assertThat(none.execute(new SandboxRequest("x", java.util.List.of(), DockerRunnerScriptTest.LIMITS)).status())
                .isEqualTo(SandboxResult.Status.FAILED);

        props.setBackend(null);
        assertThat(config.codeSandbox(props, new DefaultProcessRunner(), Clock.systemUTC()).available()).isFalse();
    }
}
