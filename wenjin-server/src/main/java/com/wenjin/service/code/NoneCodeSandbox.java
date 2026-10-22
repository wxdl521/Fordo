package com.wenjin.service.code;

/** backend=none：恒不可用（关闭代码执行但保留开关语义）。 */
final class NoneCodeSandbox implements CodeSandbox {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public SandboxResult execute(SandboxRequest request) {
        return SandboxResult.failed("backend=none");
    }
}
