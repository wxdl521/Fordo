package com.wenjin.service.code;

import com.wenjin.config.CodeCheckProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class Judge0CodeSandboxTest {

    private static final String BASE = "http://judge0.local:2358";
    private static final String SUBMIT = BASE + "/submissions?base64_encoded=true&wait=true";
    private static final String SOURCE = "public class Main { public static void main(String[] a) {} }";

    private CodeCheckProperties.Judge0 cfg;
    private MockRestServiceServer server;
    private Judge0CodeSandbox sandbox;

    @BeforeEach
    void setUp() {
        cfg = new CodeCheckProperties.Judge0();
        cfg.setBaseUrl(BASE + "/");
        cfg.setApiKey("judge0-token-123");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sandbox = new Judge0CodeSandbox(cfg, builder.build());
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String resp(int status, String stdout, String stderr, String compile, Integer exit) {
        return "{\"status\":{\"id\":" + status + ",\"description\":\"x\"},"
                + "\"stdout\":" + (stdout == null ? "null" : "\"" + b64(stdout) + "\"") + ","
                + "\"stderr\":" + (stderr == null ? "null" : "\"" + b64(stderr) + "\"") + ","
                + "\"compile_output\":" + (compile == null ? "null" : "\"" + b64(compile) + "\"") + ","
                + "\"time\":\"0.215\",\"exit_code\":" + exit + "}";
    }

    private SandboxResult exec(List<String> stdins) {
        return sandbox.execute(new SandboxRequest(SOURCE, stdins, DockerRunnerScriptTest.LIMITS));
    }

    @Test
    @DisplayName("逐用例提交：base64 源码/输入、限额、禁网、鉴权头；Accepted 映射为退出码 0")
    void submit_mapsLimitsAndAccepted() {
        server.expect(once(), requestTo(SUBMIT)).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Auth-Token", "judge0-token-123"))
                .andExpect(jsonPath("$.source_code").value(b64(SOURCE)))
                .andExpect(jsonPath("$.stdin").value(b64("1 2")))
                .andExpect(jsonPath("$.language_id").value(62))
                .andExpect(jsonPath("$.cpu_time_limit").value(3.0))
                .andExpect(jsonPath("$.wall_time_limit").value(4.0))
                .andExpect(jsonPath("$.memory_limit").value(256 * 1024))
                .andExpect(jsonPath("$.max_processes_and_or_threads").value(64))
                .andExpect(jsonPath("$.enable_network").value(false))
                .andRespond(withSuccess(resp(3, "3\n", null, null, 0), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(SUBMIT))
                .andExpect(jsonPath("$.stdin").value(b64("x")))
                .andRespond(withSuccess(resp(11, "", "Exception in thread \"main\"", null, 1),
                        MediaType.APPLICATION_JSON));

        SandboxResult r = exec(List.of("1 2", "x"));

        server.verify();
        assertThat(r.status()).isEqualTo(SandboxResult.Status.COMPLETED);
        assertThat(r.cases()).hasSize(2);
        assertThat(r.cases().get(0).exitCode()).isZero();
        assertThat(r.cases().get(0).stdout()).isEqualTo("3\n");
        assertThat(r.cases().get(0).durationMs()).isEqualTo(215);
        assertThat(r.cases().get(1).exitCode()).isEqualTo(1);
        assertThat(r.cases().get(1).stderr()).contains("Exception");
    }

    @Test
    @DisplayName("编译错误：首个用例即停，回编译输出")
    void compileError_stopsEarly() {
        server.expect(once(), requestTo(SUBMIT))
                .andRespond(withSuccess(resp(6, null, null, "Main.java:1: error: x", null), MediaType.APPLICATION_JSON));
        SandboxResult r = exec(List.of("a", "b"));
        server.verify();
        assertThat(r.status()).isEqualTo(SandboxResult.Status.COMPILE_ERROR);
        assertThat(r.compileOutput()).contains("error: x");
    }

    @Test
    @DisplayName("TLE → timedOut；SIGXFSZ → 输出超限；输出超过 output-max-bytes 被截断标记")
    void limitsMapping() {
        server.expect(once(), requestTo(SUBMIT))
                .andRespond(withSuccess(resp(5, "", null, null, null), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(SUBMIT))
                .andRespond(withSuccess(resp(8, "xx", null, null, null), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(SUBMIT))
                .andRespond(withSuccess(resp(3, "y".repeat(70_000), null, null, 0), MediaType.APPLICATION_JSON));
        assertThat(exec(List.of("a")).cases().get(0).timedOut()).isTrue();
        assertThat(exec(List.of("b")).cases().get(0).stdoutExceeded()).isTrue();
        SandboxResult big = exec(List.of("c"));
        assertThat(big.cases().get(0).stdoutExceeded()).isTrue();
        assertThat(big.cases().get(0).stdout()).hasSize(65_536);
        server.verify();
    }

    @Test
    @DisplayName("fail-fast：首个 TLE / 输出超限用例之后不再提交剩余用例")
    void failFast() {
        server.expect(once(), requestTo(SUBMIT))
                .andRespond(withSuccess(resp(3, "1\n", null, null, 0), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(SUBMIT))
                .andRespond(withSuccess(resp(5, "", null, null, null), MediaType.APPLICATION_JSON));
        SandboxResult r = exec(List.of("a", "b", "c", "d"));
        assertThat(r.status()).isEqualTo(SandboxResult.Status.COMPLETED);
        assertThat(r.cases()).hasSize(2);
        server.verify(); // 只发了两次请求
    }

    @Test
    @DisplayName("内部错误 / HTTP 失败 / 未配置 → FAILED，不抛")
    void failures() {
        server.expect(once(), requestTo(SUBMIT))
                .andRespond(withSuccess(resp(13, null, null, null, null), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(SUBMIT)).andRespond(withServerError());
        assertThat(exec(List.of("a")).status()).isEqualTo(SandboxResult.Status.FAILED);
        assertThat(exec(List.of("a")).status()).isEqualTo(SandboxResult.Status.FAILED);
        server.verify();

        cfg.setBaseUrl("");
        assertThat(exec(List.of("a")).status()).isEqualTo(SandboxResult.Status.FAILED);
    }

    @Test
    @DisplayName("available（阻塞健康检查，由后台刷新线程调用）：未配置基址即 false；GET /about 成功才 true；热改基址立即用新地址")
    void available() {
        cfg.setBaseUrl("");
        assertThat(sandbox.available()).isFalse();

        cfg.setBaseUrl(BASE);
        server.expect(once(), requestTo(BASE + "/about")).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Auth-Token", "judge0-token-123"))
                .andRespond(withSuccess("{\"version\":\"1.13.1\"}", MediaType.APPLICATION_JSON));
        assertThat(sandbox.available()).isTrue();
        server.verify();

        server.reset();
        server.expect(once(), requestTo(BASE + "/about")).andRespond(withServerError());
        assertThat(sandbox.available()).isFalse();
        server.verify();
    }
}
