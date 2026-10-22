package com.wenjin.service.companion;

/**
 * 工具受众（方向 7）：学生伴侣工具只对学生会话开放，教师助教工具只对教师会话开放。
 * {@link ToolGovernor} 会拒绝受众与 {@link ToolContext#audience()} 不符的调用。
 */
public enum ToolAudience {
    STUDENT,
    TEACHER
}
