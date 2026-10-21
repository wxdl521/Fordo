package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 伴侣空会话 opener（GET /api/companion/opener）：问候语 + 动作卡 + 事件型提示条。零 LLM、确定性。
 */
@Data
public class CompanionOpenerVO {

    private String greeting;
    private List<Action> actions = new ArrayList<>();
    private List<Hint> hints = new ArrayList<>();

    /** 点击后发送给伴侣的内容。nodeCode 仅提示条使用；reviewPracticeSessionId 仅错题复盘使用。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Cta {
        private String prompt;
        private Long reviewPracticeSessionId;
        private String nodeCode;
    }

    /** kind: REVIEW_MISTAKES / REVIEW_DUE / PLAN_TASK / WEAK_NODE / FRONTIER */
    @Data
    public static class Action {
        private String kind;
        private String title;
        private String reason;
        private String nodeCode;
        private String nodeName;
        private Cta cta;
    }

    /** hintType: ERROR_BURST / DECAY / PLAN_BEHIND */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Hint {
        private String hintKey;
        private String hintType;
        private String title;
        private String reason;
        private Cta cta;
    }
}
