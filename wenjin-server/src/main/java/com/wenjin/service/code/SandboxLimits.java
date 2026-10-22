package com.wenjin.service.code;

import com.wenjin.config.LimitsProperties;

/**
 * 一次执行使用的资源上限快照（来自 {@code wenjin.limits.code.*}）。
 */
public record SandboxLimits(int compileTimeoutSeconds,
                            int runTimeoutSeconds,
                            int memoryMb,
                            int jvmHeapMb,
                            double cpus,
                            int pids,
                            int outputMaxBytes) {

    public SandboxLimits {
        if (compileTimeoutSeconds < 1 || runTimeoutSeconds < 1 || memoryMb < 32 || jvmHeapMb < 16
                || cpus <= 0 || pids < 8 || outputMaxBytes < 1) {
            throw new IllegalArgumentException("invalid sandbox limits");
        }
    }

    public static SandboxLimits from(LimitsProperties.Code c) {
        return new SandboxLimits(c.getCompileTimeoutSeconds(), c.getRunTimeoutSeconds(), c.getMemoryMb(),
                c.getJvmHeapMb(), c.getCpus(), c.getPids(), c.getOutputMaxBytes());
    }
}
