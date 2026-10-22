package com.wenjin.service.code;

import java.util.List;

/**
 * 一次沙箱执行：同一份源码编译一次，对 {@code stdins} 中每份输入各运行一次（顺序即用例序号 0..n-1）。
 */
public record SandboxRequest(String source, List<String> stdins, SandboxLimits limits) {

    public SandboxRequest {
        stdins = stdins == null ? List.of() : List.copyOf(stdins);
    }
}
