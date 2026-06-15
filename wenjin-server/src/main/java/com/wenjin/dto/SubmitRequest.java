package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 学生交卷请求：提交一批答题结果。
 * 每条 Answer 对应一道题目及学生选择的选项标识。
 */
@Data
public class SubmitRequest {

    /** 学生 ID */
    private Long studentId;

    /** 课程 ID */
    private Long courseId;

    /** 作答列表（顺序与发卷顺序一致） */
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
