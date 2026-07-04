package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 自适应诊断作答请求（M2 T4）：提交当前探针的全部题目作答。
 *
 * <p>{@code answers} 的 questionId 集合必须与当前探针冻结题目集合一致（顺序无关、全集齐）；
 * 部分集合/混入外来题/空列表/重复 questionId 一律 BAD_REQUEST；
 * 与"上一探针"集合一致时按幂等重放处理（见 AdaptiveDiagnosticService.answer）。
 */
@Data
public class AdaptiveAnswerRequest {

    /** 学生 ID（service 层反查 session 属主校验，controller 由 T6 挂 assertSelf）。 */
    private Long studentId;

    /** 逐题作答（探针只含客观题，服务端判分，绝不信任客户端对错）。 */
    private List<AnswerItem> answers;

    /** 单题作答项。 */
    @Data
    public static class AnswerItem {

        /** 题目 ID（必须属于当前探针冻结题目集）。 */
        private Long questionId;

        /** 学生作答：单选/判断为单字母，多选为逗号分隔串。 */
        private String studentAnswer;
    }
}
