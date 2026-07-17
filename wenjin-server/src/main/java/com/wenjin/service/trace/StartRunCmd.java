package com.wenjin.service.trace;

/**
 * 开启一次 agent 轨迹 run 的命令。
 *
 * @param studentId      学生用户 ID
 * @param courseId       课程 ID
 * @param conversationId 伴侣会话 ID（可空）
 * @param kind           业务类型，如 COMPANION_CHAT / DIAGNOSTIC
 * @param model          模型标识（可空）
 * @param firstInput     首条输入，仅取前 200 字作 digest（可空）
 * @param requestId      可选；为空时从当前请求 {@code RequestIdFilter.ATTR} 读取
 */
public record StartRunCmd(
        long studentId,
        long courseId,
        Long conversationId,
        String kind,
        String model,
        String firstInput,
        String requestId
) {
    public StartRunCmd {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind is required");
        }
    }

    public static StartRunCmd of(long studentId, long courseId, Long conversationId,
                                 String kind, String model, String firstInput) {
        return new StartRunCmd(studentId, courseId, conversationId, kind, model, firstInput, null);
    }
}
