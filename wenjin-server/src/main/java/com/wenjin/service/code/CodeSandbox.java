package com.wenjin.service.code;

/**
 * 沙箱后端 SPI（内部）：在隔离环境里编译 {@code Main.java} 并对每份标准输入各运行一次。
 * <p>实现只负责「执行 + 原样回传」，不做比对、脱敏、限流；这些由 {@link DefaultCodeExecutionService} 统一处理。
 * 实现<b>绝不</b>在宿主 JVM 内加载或执行学生代码，且不得抛出异常——基础设施问题一律
 * {@link SandboxResult.Status#FAILED}。</p>
 */
public interface CodeSandbox {

    /** 后端名（日志用）。 */
    String name();

    /** 后端当前可用（可缓存短时间）。 */
    boolean available();

    /** 执行；永不抛异常。 */
    SandboxResult execute(SandboxRequest request);
}
