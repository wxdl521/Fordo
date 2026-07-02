package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 练习提交请求体（M1 练习闭环 T4）。
 *
 * <p>客户端只上送题目 ID + 学生作答，判分全部由服务端完成；
 * 不携带 isCorrect / pointNodeCode / answer 字段（判分范式约定）。
 */
@Data
public class PracticeSubmitRequest {

    /** 学生 ID（由 Controller 从 CurrentUser 传入，T7 做鉴权） */
    private Long studentId;

    /** 作答列表 */
    private List<AnswerItem> answers;

    /** 单题作答 */
    @Data
    public static class AnswerItem {

        /** 题目 ID */
        private Long questionId;

        /**
         * 学生作答内容：
         * 单选/判断 = 单字母（如 "A"）；
         * 多选 = 逗号分隔（如 "A,C"）；
         * 简答 = 任意文本（服务端本期不判分）。
         */
        private String studentAnswer;
    }
}
