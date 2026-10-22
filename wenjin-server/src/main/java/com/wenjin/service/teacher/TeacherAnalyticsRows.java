package com.wenjin.service.teacher;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * {@link com.wenjin.mapper.TeacherAnalyticsMapper} 的行模型（纯学习数据；不含学生记忆或对话正文）。
 */
public final class TeacherAnalyticsRows {

    private TeacherAnalyticsRows() {
    }

    @Data
    public static class RosterRow {
        private Long studentId;
        private String realName;
        private String username;
    }

    /** 每名选课学生的作答活动汇总（只统计已判分作答 is_correct IS NOT NULL）。 */
    @Data
    public static class StudentActivityRow {
        private Long studentId;
        private LocalDateTime lastAnswerAt;
        private Integer answers7;
        private Integer wrong7;
        private Integer answers14;
        private Integer correct14;
    }

    @Data
    public static class DailyAnswerRow {
        private String day;
        private Integer answers;
        private Integer activeStudents;
    }

    @Data
    public static class DailyCompanionRow {
        private String day;
        private Integer conversations;
    }

    @Data
    public static class QuestionErrorRow {
        private Long questionId;
        private String stem;
        private Integer type;
        private String nodeCode;
        private String nodeName;
        private Integer attempts;
        private Integer wrongCount;
    }

    @Data
    public static class AnswerChoiceRow {
        private Long questionId;
        private String studentAnswer;
        private Integer cnt;
    }
}
