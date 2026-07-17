package com.wenjin.service.trace;

/**
 * 一次 agent step 的记录载荷。
 *
 * @param stepType   见 {@link com.wenjin.support.AgentStepType}
 * @param name       工具名或 phase 名
 * @param status     见 {@link com.wenjin.support.AgentStepStatus}
 * @param inputJson  输入（超限截断）
 * @param outputJson 输出（超限截断）
 * @param tokensIn   本步输入 token
 * @param tokensOut  本步输出 token
 * @param durationMs 本步耗时（可空）
 * @param errorCode  稳定错误码（可空）
 */
public record StepRecord(
        String stepType,
        String name,
        int status,
        String inputJson,
        String outputJson,
        int tokensIn,
        int tokensOut,
        Integer durationMs,
        String errorCode
) {
    public StepRecord {
        if (stepType == null || stepType.isBlank()) {
            throw new IllegalArgumentException("stepType is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }
}
