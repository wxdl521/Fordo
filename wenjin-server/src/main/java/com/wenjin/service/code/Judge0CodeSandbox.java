package com.wenjin.service.code;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenjin.config.CodeCheckProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自托管 Judge0 后端：逐用例 {@code POST /submissions?base64_encoded=true&wait=true}（源码随每个用例重新编译；
 * 用例数 ≤ max-cases，可接受）。Judge0 自身负责隔离（isolate 沙箱 + 独立主机），本类只映射限额与状态。
 * 期望输出不发给 Judge0，比对仍在宿主侧。基址与 token 每次调用现读（管理后台可热改）。
 *
 * <p>状态映射（Judge0 CE 1.13）：3 Accepted → 退出码 0；5 TLE → timedOut；6 编译错误；8 SIGXFSZ → 输出超限；
 * 7/9/10/11/12 运行错误；其它（1/2 仍在排队、13 内部错误、14 Exec Format Error）→ FAILED。</p>
 */
public class Judge0CodeSandbox implements CodeSandbox {

    private static final Logger log = LoggerFactory.getLogger(Judge0CodeSandbox.class);
    static final String TOKEN_HEADER = "X-Auth-Token";

    private final CodeCheckProperties.Judge0 cfg;
    private final RestClient client;
    private final Clock clock;

    private volatile Cached cached;

    private record Cached(String key, boolean value, Instant expiresAt) {
    }

    public Judge0CodeSandbox(CodeCheckProperties.Judge0 cfg, RestClient client, Clock clock) {
        this.cfg = cfg;
        this.client = client;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "judge0";
    }

    @Override
    public boolean available() {
        String base = base();
        if (base == null) {
            return false;
        }
        String key = base + "|" + (cfg.getApiKey() == null ? 0 : cfg.getApiKey().hashCode());
        Instant now = clock.instant();
        Cached c = cached;
        if (c != null && c.key().equals(key) && now.isBefore(c.expiresAt())) {
            return c.value();
        }
        boolean ok;
        try {
            client.get().uri(base + "/about").headers(h -> auth(h)).retrieve().toBodilessEntity();
            ok = true;
        } catch (RuntimeException e) {
            log.warn("Judge0 不可用：{}", e.getClass().getSimpleName());
            ok = false;
        }
        cached = new Cached(key, ok, now.plusSeconds(Math.max(1, cfg.getAvailabilityCacheSeconds())));
        return ok;
    }

    @Override
    public SandboxResult execute(SandboxRequest request) {
        String base = base();
        if (base == null) {
            return SandboxResult.failed("judge0 base-url not configured");
        }
        SandboxLimits l = request.limits();
        List<SandboxResult.CaseRun> cases = new ArrayList<>();
        try {
            List<String> stdins = request.stdins();
            for (int i = 0; i < stdins.size(); i++) {
                JsonNode r = client.post()
                        .uri(base + "/submissions?base64_encoded=true&wait=true")
                        .headers(this::auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body(request.source(), stdins.get(i), l))
                        .retrieve()
                        .body(JsonNode.class);
                if (r == null) {
                    return SandboxResult.failed("judge0 empty response");
                }
                int status = r.path("status").path("id").asInt(-1);
                if (status == 6) {
                    return new SandboxResult(SandboxResult.Status.COMPILE_ERROR,
                            text(r, "compile_output", l.outputMaxBytes()), List.of(), null);
                }
                if (status < 3 || status > 12) {
                    log.warn("Judge0 判题异常 status={}", status);
                    return SandboxResult.failed("judge0 status " + status);
                }
                byte[] so = bytes(r, "stdout");
                byte[] se = bytes(r, "stderr");
                int exit = status == 3 ? 0 : r.path("exit_code").isInt() ? r.path("exit_code").asInt() : 1;
                if (status == 5) {
                    exit = 124;
                }
                long ms = Math.round(r.path("time").asDouble(0) * 1000);
                cases.add(new SandboxResult.CaseRun(i, exit, status == 5, ms,
                        utf8(so, l.outputMaxBytes()), status == 8 || so.length > l.outputMaxBytes(),
                        utf8(se, l.outputMaxBytes()), se.length > l.outputMaxBytes()));
            }
            return new SandboxResult(SandboxResult.Status.COMPLETED, "", cases, null);
        } catch (RuntimeException e) {
            log.warn("Judge0 调用失败：{}", e.getClass().getSimpleName());
            return SandboxResult.failed("judge0 " + e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> body(String source, String stdin, SandboxLimits l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source_code", b64(source));
        m.put("language_id", cfg.getLanguageId());
        m.put("stdin", b64(stdin == null ? "" : stdin));
        m.put("cpu_time_limit", (double) l.runTimeoutSeconds());
        m.put("wall_time_limit", (double) l.runTimeoutSeconds() + 1);
        m.put("memory_limit", l.memoryMb() * 1024);
        m.put("max_processes_and_or_threads", l.pids());
        m.put("max_file_size", Math.max(1, l.outputMaxBytes() / 1024));
        m.put("enable_network", false);
        return m;
    }

    private void auth(org.springframework.http.HttpHeaders h) {
        String key = cfg.getApiKey();
        if (key != null && !key.isBlank()) {
            h.set(TOKEN_HEADER, key);
        }
    }

    private String base() {
        String b = cfg.getBaseUrl();
        if (b == null || b.isBlank()) {
            return null;
        }
        b = b.strip();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(JsonNode r, String field) {
        JsonNode v = r.get(field);
        if (v == null || v.isNull()) {
            return new byte[0];
        }
        try {
            return Base64.getMimeDecoder().decode(v.asText());
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private static String text(JsonNode r, String field, int max) {
        return utf8(bytes(r, field), max);
    }

    private static String utf8(byte[] b, int max) {
        return new String(b, 0, Math.min(b.length, Math.max(0, max)), StandardCharsets.UTF_8);
    }
}
