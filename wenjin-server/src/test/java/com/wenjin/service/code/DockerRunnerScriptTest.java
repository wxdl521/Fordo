package com.wenjin.service.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class DockerRunnerScriptTest {

    static final SandboxLimits LIMITS = new SandboxLimits(15, 3, 256, 128, 1.0, 64, 65_536);

    static String b64(String s) {
        return s.isEmpty() ? "-" : Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] out(String... lines) {
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    // ───────────── render ─────────────

    @Test
    @DisplayName("runner 脚本：POSIX sh、LF 换行；参数只有数字；javac/java 都套 timeout 且带 -Xmx；输出 head -c 截断")
    void render_containsLimits() {
        String s = DockerRunnerScript.render(LIMITS, 4);
        assertThat(s).startsWith("#!/bin/sh\n").doesNotContain("\r");
        assertThat(s).contains("N=4\n", "CT=15\n", "RT=3\n", "LIM=65537\n", "HEAP=128\n");
        assertThat(s).contains("timeout -s KILL \"$CT\" javac");
        assertThat(s).contains("timeout -s KILL \"$RT\" java");
        assertThat(s).contains("-Xmx${HEAP}m", "-J-Xmx${HEAP}m", "-XX:+ExitOnOutOfMemoryError");
        assertThat(s).contains("head -c \"$LIM\"");
        assertThat(s).contains("-cp \"$T/out\" Main < \"$W/in/$i.txt\"");
        // 学生程序 stdout/stderr 只进文件，经 base64 回传
        assertThat(s).contains("| head -c \"$LIM\" > \"$T/so\"", "2> \"$T/ef\"", "base64 -w 0");
        assertThat(s).contains("unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS");
        assertThat(s).contains("@@WJ1 BEGIN", "@@WJ1 COMPILE", "@@WJ1 CASE", "@@WJ1 END", "@@WJ1 FATAL");
        // 需要的 coreutils 先自检
        assertThat(s).contains("for t in timeout head base64 date mkfifo javac java");
        // timeout -s KILL 杀到自身时返回 137：按耗时归一为 124（编译与运行两处）
        assertThat(s).contains("if [ \"$rc\" -eq 137 ] && [ $((c1 - c0)) -ge $((CT * 1000)) ]; then rc=124; fi");
        assertThat(s).contains("if [ \"$r\" -eq 137 ] && [ $((t1 - t0)) -ge $((RT * 1000)) ]; then r=124; fi");
    }

    @Test
    @DisplayName("宿主侧 stdout 读取上限覆盖最坏情况（每用例两份 base64 满额输出）")
    void stdoutCap_coversWorstCase() {
        int cap = DockerRunnerScript.hostStdoutCap(LIMITS, 20);
        int perField = (65_537 + 2) / 3 * 4;
        assertThat(cap).isGreaterThan(41 * perField);
    }

    // ───────────── parse ─────────────

    @Test
    @DisplayName("协议解析：BEGIN / COMPILE / CASE（base64 解码）/ END")
    void parse_fullRun() {
        DockerRunnerScript.Parsed p = DockerRunnerScript.parse(out(
                "@@WJ1 BEGIN",
                "@@WJ1 COMPILE 0 -",
                "@@WJ1 CASE 0 0 412 " + b64("3\n") + " -",
                "@@WJ1 CASE 1 1 380 - " + b64("Exception in thread \"main\" java.lang.ArithmeticException"),
                "@@WJ1 END"), 65_536);
        assertThat(p.begun()).isTrue();
        assertThat(p.ended()).isTrue();
        assertThat(p.fatal()).isNull();
        assertThat(p.compileExitCode()).isZero();
        assertThat(p.compileOutput()).isEmpty();
        assertThat(p.cases()).hasSize(2);
        SandboxResult.CaseRun c0 = p.cases().get(0);
        assertThat(c0.index()).isZero();
        assertThat(c0.exitCode()).isZero();
        assertThat(c0.durationMs()).isEqualTo(412);
        assertThat(c0.stdout()).isEqualTo("3\n");
        assertThat(c0.stderr()).isEmpty();
        assertThat(c0.timedOut()).isFalse();
        SandboxResult.CaseRun c1 = p.cases().get(1);
        assertThat(c1.exitCode()).isEqualTo(1);
        assertThat(c1.stderr()).contains("ArithmeticException");
    }

    @Test
    @DisplayName("退出码 124 = 容器内 timeout 杀掉；超过 output-max-bytes 的输出被截断并标记")
    void parse_timeoutAndExceeded() {
        String big = "x".repeat(101);
        DockerRunnerScript.Parsed p = DockerRunnerScript.parse(out(
                "@@WJ1 BEGIN",
                "@@WJ1 COMPILE 0 -",
                "@@WJ1 CASE 0 124 3004 " + b64(big) + " " + b64("e".repeat(100)),
                "@@WJ1 END"), 100);
        SandboxResult.CaseRun c = p.cases().get(0);
        assertThat(c.timedOut()).isTrue();
        assertThat(c.stdoutExceeded()).isTrue();
        assertThat(c.stdout()).hasSize(100);
        assertThat(c.stderrExceeded()).isFalse();
        assertThat(c.stderr()).hasSize(100);
    }

    @Test
    @DisplayName("编译失败：COMPILE 非零 + 编译输出")
    void parse_compileError() {
        DockerRunnerScript.Parsed p = DockerRunnerScript.parse(out(
                "@@WJ1 BEGIN",
                "@@WJ1 COMPILE 1 " + b64("/work/Main.java:3: error: ';' expected"),
                "@@WJ1 END"), 65_536);
        assertThat(p.compileExitCode()).isEqualTo(1);
        assertThat(p.compileOutput()).contains("';' expected");
        assertThat(p.cases()).isEmpty();
    }

    @Test
    @DisplayName("FATAL / 缺 BEGIN / 未结束（截断）都能识别")
    void parse_incomplete() {
        assertThat(DockerRunnerScript.parse(out("@@WJ1 BEGIN", "@@WJ1 FATAL missing-timeout"), 10).fatal())
                .isEqualTo("missing-timeout");
        DockerRunnerScript.Parsed none = DockerRunnerScript.parse("Unable to find image".getBytes(StandardCharsets.UTF_8), 10);
        assertThat(none.begun()).isFalse();
        DockerRunnerScript.Parsed partial = DockerRunnerScript.parse(out(
                "@@WJ1 BEGIN", "@@WJ1 COMPILE 0 -", "@@WJ1 CASE 0 0 5 - -"), 10);
        assertThat(partial.ended()).isFalse();
        assertThat(partial.cases()).hasSize(1);
        assertThat(DockerRunnerScript.parse(null, 10).begun()).isFalse();
    }

    @Test
    @DisplayName("容错：畸形行、越界/重复序号、非法 base64、END 之后的行一律忽略")
    void parse_ignoresGarbage() {
        DockerRunnerScript.Parsed p = DockerRunnerScript.parse(out(
                "@@WJ1 BEGIN",
                "garbage line",
                "@@WJ1 COMPILE 0 -",
                "@@WJ1 CASE x 0 5 - -",
                "@@WJ1 CASE 0 0 5 " + b64("first") + " -",
                "@@WJ1 CASE 0 0 5 " + b64("forged") + " -",
                "@@WJ1 CASE 1 0 5 !!notbase64!! -",
                "@@WJ1 CASE 2 0 5",
                "@@WJ1 CASE -1 0 5 - -",
                "@@WJ1 END",
                "@@WJ1 CASE 3 0 5 - -"), 100);
        assertThat(p.cases()).extracting(SandboxResult.CaseRun::index).containsExactly(0);
        assertThat(p.cases().get(0).stdout()).isEqualTo("first");
        assertThat(p.ended()).isTrue();
    }
}
