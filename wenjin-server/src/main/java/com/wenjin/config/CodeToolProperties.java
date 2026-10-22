package com.wenjin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 伴侣工具 {@code run_code_check} 的调参（前缀 {@code wenjin.companion.code-tool}）。
 * <p>沙箱本身的限额（源码长度、并发、超时等）属于 {@code wenjin.limits.code.*}（C1）；这里只管工具层：
 * 每 run 次数、入参上限、回给模型的摘要长度。
 */
@Data
@ConfigurationProperties(prefix = "wenjin.companion.code-tool")
public class CodeToolProperties {

    /** 每个 agent run 最多调用 run_code_check 的次数（含失败/被拒的调用）。 */
    private int maxPerRun = 1;

    /** {@code code} 参数最大字符数（与 wenjin.limits.code.source-max-chars 对齐，合并时统一）。 */
    private int sourceMaxChars = 10000;

    /** {@code stdin} 参数最大字符数。 */
    private int stdinMaxChars = 2000;

    /** 回给模型的 stdout 预览字符数。 */
    private int stdoutPreviewChars = 300;

    /** 回给模型的 stderr 预览字符数。 */
    private int stderrPreviewChars = 200;

    /** 回给模型的编译错误行数 / 字符数上限（取先到者）。 */
    private int compileErrorLines = 8;
    private int compileErrorChars = 600;

    /** 回给模型的失败用例条数，以及每条用例每个字段的预览字符数。 */
    private int maxFailureCases = 3;
    private int caseFieldChars = 100;
}
