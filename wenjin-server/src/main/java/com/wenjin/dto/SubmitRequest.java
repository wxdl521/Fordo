package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 学生交卷请求：提交一批答题结果。
 * 每条 Answer 对应一道题目及学生选择的选项标识。
 * <p>固定卷必须以 {@code sessionToken} 绑定冻结会话；题集须与会话精确匹配。</p>
 */
@Data
public class SubmitRequest {

    /** 学生 ID（由 Controller 从 principal 写入，服务端以会话归属为准） */
    private Long studentId;

    /** 课程 ID（可选；若提供须与会话 course_id 一致） */
    private Long courseId;

    /** 组卷返回的冻结会话令牌（必填） */
    private String sessionToken;

    /** 作答列表（questionId 集合必须与冻结题集精确一致，无重复） */
    private List<Answer> answers;

    /** 单道题的作答：题目 ID + 学生所选选项标识 */
    @Data
    public static class Answer {

        /** 题目 ID */
        private Long questionId;

        /** 学生所选选项标识（A/B/C/D；未作答可为 null） */
        private String optionKey;
    }
}
