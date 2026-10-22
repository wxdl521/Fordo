package com.wenjin.service.code;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerRunnerScriptTest {

    static final SandboxLimits LIMITS = new SandboxLimits(15, 3, 256, 128, 1.0, 64, 65_536);

    // ───────────── compile.sh ─────────────

    @Test
    @DisplayName("compile.sh：POSIX sh、LF；javac 不跑注解处理器、只写 /classes、诊断并入 stdout、产物 umask 000")
    void compileScript_shape() {
        String s = DockerRunnerScript.compileScript();
        assertThat(s).startsWith("#!/bin/sh\n").doesNotContain("\r");
        assertThat(s).contains("HEAP=$1\n", "case \"$HEAP\" in ''|*[!0-9]*) exit 125 ;; esac");
        assertThat(s).contains("command -v javac >/dev/null 2>&1 || exit 125");
        assertThat(s).contains("unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH");
        assertThat(s).contains("umask 000");
        assertThat(s).contains("exec javac -J-Xmx${HEAP}m", "-proc:none", "-encoding UTF-8",
                "-d /classes /src/Main.java 2>&1");
        // 编译阶段不接触任何用例输入，也没有旧的单容器协议
        assertThat(s).doesNotContain("/in", "/work", "@@WJ1");
    }

    // ───────────── case.sh ─────────────

    @Test
    @DisplayName("case.sh：只读 /in/stdin.txt 与 /classes；stdout/stderr 容器内 head -c 截断；退出码规整（124–127→1、钳到 0–255）")
    void caseScript_shape() {
        String s = DockerRunnerScript.caseScript();
        assertThat(s).startsWith("#!/bin/sh\n").doesNotContain("\r");
        assertThat(s).contains("LIM=$1\n", "HEAP=$2\n", "case \"$LIM$HEAP\" in ''|*[!0-9]*) exit 125 ;; esac");
        assertThat(s).contains("for t in java head mkfifo cat; do", "[ -r /in/stdin.txt ] || exit 125");
        assertThat(s).contains("unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH");
        assertThat(s).contains("-cp /classes Main < /in/stdin.txt 2> \"$T/ef\"");
        assertThat(s).contains("-Xmx${HEAP}m", "-XX:+ExitOnOutOfMemoryError");
        assertThat(s).contains("| head -c \"$LIM\"\n", "head -c \"$LIM\" < \"$T/ef\" >&2 &");
        assertThat(s).contains("case \"$r\" in ''|*[!0-9]*|????*) r=255 ;; esac",
                "if [ \"$r\" -gt 255 ]; then r=255; fi",
                "if [ \"$r\" -ge 124 ] && [ \"$r\" -le 127 ]; then r=1; fi",
                "exit \"$r\"");
        // 没有任何多用例循环 / 结果协议：一个容器只跑一个用例
        assertThat(s).doesNotContain("/work", "@@WJ1", "while");
    }

    @Test
    @DisplayName("宿主读取上限 = output-max-bytes + 1；docker/脚本基础设施退出码 = 125/126/127")
    void capsAndInfraCodes() {
        assertThat(DockerRunnerScript.caseOutputCap(LIMITS)).isEqualTo(65_537);
        assertThat(DockerRunnerScript.isInfrastructureExit(125)).isTrue();
        assertThat(DockerRunnerScript.isInfrastructureExit(126)).isTrue();
        assertThat(DockerRunnerScript.isInfrastructureExit(127)).isTrue();
        assertThat(DockerRunnerScript.isInfrastructureExit(124)).isFalse();
        assertThat(DockerRunnerScript.isInfrastructureExit(137)).isFalse();
        assertThat(DockerRunnerScript.isInfrastructureExit(1)).isFalse();
    }

    // ───────────── case.sh 真跑（POSIX 宿主；用假 java 代替 JVM）─────────────

    @TempDir
    Path tmp;

    private record ShRun(int exit, String stdout, String stderr) {
    }

    /** 把 case.sh 的容器内路径换成临时目录，用假的 java 跑一次。 */
    private ShRun runCase(String fakeJavaBody, String stdin, int lim) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "case.sh 行为测试只在 POSIX 宿主（CI）上跑");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path java = bin.resolve("java");
        Files.writeString(java, "#!/bin/sh\n" + fakeJavaBody + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path in = Files.createDirectories(tmp.resolve("in"));
        Files.writeString(in.resolve("stdin.txt"), stdin, StandardCharsets.UTF_8);
        Path work = Files.createDirectories(tmp.resolve("t"));
        String script = DockerRunnerScript.caseScript()
                .replace("/in/stdin.txt", in.resolve("stdin.txt").toString())
                .replace("T=/tmp/wj", "T=" + work.resolve("wj"));
        Path sh = tmp.resolve("case.sh");
        Files.writeString(sh, script, StandardCharsets.UTF_8);
        String path = bin + ":" + System.getenv().getOrDefault("PATH", "/usr/bin:/bin");
        List<String> cmd = List.of("/usr/bin/env", "PATH=" + path, "RCFILE=" + work.resolve("wj").resolve("rc"),
                "/bin/sh", sh.toString(), String.valueOf(lim), "64");
        ProcessRunner.Result r = new DefaultProcessRunner().run(cmd, Duration.ofSeconds(20), 1 << 20, 1 << 20);
        assertThat(r.timedOut()).isFalse();
        return new ShRun(r.exitCode(), new String(r.stdout(), StandardCharsets.UTF_8), r.stderr());
    }

    @Test
    @DisplayName("case.sh 真跑：stdin 透传、stdout/stderr 分流并各自截断到 LIM 字节")
    void caseScript_runsAndTruncates() throws Exception {
        ShRun echo = runCase("cat; echo err-line >&2", "1 2\n", 1000);
        assertThat(echo.exit()).isZero();
        assertThat(echo.stdout()).isEqualTo("1 2\n");
        assertThat(echo.stderr()).isEqualTo("err-line\n");

        ShRun flood = runCase("i=0; while [ $i -lt 2000 ]; do printf xxxxxxxxxx; printf eeeeeeeeee >&2; i=$((i+1)); done",
                "", 100);
        assertThat(flood.stdout()).hasSize(100);
        assertThat(flood.stderr()).hasSize(100);
    }

    @Test
    @DisplayName("case.sh 真跑：学生退出码 124–127 改写为 1（不能冒充 docker 失败），其它原样")
    void caseScript_sanitisesExitCodes() throws Exception {
        assertThat(runCase("exit 3", "", 100).exit()).isEqualTo(3);
        for (int code : new int[]{124, 125, 126, 127}) {
            assertThat(runCase("exit " + code, "", 100).exit()).as("exit " + code).isEqualTo(1);
        }
        assertThat(runCase("exit 0", "", 100).exit()).isZero();
    }

    @Test
    @DisplayName("case.sh 真跑：学生改写 /tmp 里的 rc 文件（超大数 / 非数字）→ 钳为 255，绝不变成 125")
    void caseScript_forgedRcFile() throws Exception {
        // 后台子进程持有 stdout 管道，等 java 退出、脚本写完 rc 之后再改写它（head 会一直等到它退出）
        assertThat(runCase("(sleep 1; echo 381 > \"$RCFILE\") & exit 0", "", 100).exit()).isEqualTo(255);
        assertThat(runCase("(sleep 1; echo abc > \"$RCFILE\") & exit 0", "", 100).exit()).isEqualTo(255);
        assertThat(runCase("(sleep 1; echo 125 > \"$RCFILE\") & exit 0", "", 100).exit()).isEqualTo(1);
    }
}
