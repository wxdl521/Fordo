package com.wenjin.service.teacher;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 教师助教学情结果模型。字段名即工具返回给模型的 JSON 字段名（紧凑、NON_NULL）。
 * 所有分数均为 0–100 的有效掌握度（读时衰减后），保留 1 位小数。
 */
public final class TeacherAnalyticsModels {

    private TeacherAnalyticsModels() {
    }

    public static final String STATUS_OK = "ok";
    public static final String STATUS_AMBIGUOUS = "ambiguous";
    public static final String STATUS_NOT_FOUND = "not_found";

    /** 学生（姓名 = real_name，空则脱敏用户名）。 */
    public record StudentRef(long studentId, String name) {
    }

    /** 节点引用。 */
    public record NodeRef(String nodeCode, String name) {
    }

    /** 全班学生级分布：按学生平均有效分归档；未诊断 = 无任何掌握记录。 */
    public record StudentDistribution(int mastered, int weak, int notLearned, int undiagnosed) {
    }

    public record WeakNodeView(String nodeCode, String name, String chapter, double avgEffective, int sampleSize) {
    }

    public record ChapterView(String chapter, int nodes, double avgEffective, int sampleSize) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClassOverview(int students,
                                int diagnosed,
                                Double avgEffective,
                                StudentDistribution distribution,
                                List<WeakNodeView> weakestNodes,
                                List<ChapterView> chapters,
                                int active7d,
                                String note) {
    }

    /** 关注原因代码。 */
    public static final class Reason {
        public static final String LOW_MASTERY = "LOW_MASTERY";
        public static final String UNDIAGNOSED = "UNDIAGNOSED";
        public static final String DROP = "DROP";
        public static final String NO_PRACTICE = "NO_PRACTICE";
        public static final String MANY_WRONG = "MANY_WRONG";
        public static final String REVIEW_BACKLOG = "REVIEW_BACKLOG";
        public static final List<String> ALL = List.of(
                LOW_MASTERY, UNDIAGNOSED, DROP, NO_PRACTICE, MANY_WRONG, REVIEW_BACKLOG);

        private Reason() {
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AttentionItem(long studentId,
                                String name,
                                Double avgEffective,
                                int score,
                                List<String> reasonCodes,
                                List<String> reasons,
                                String lastPracticeAt,
                                Integer daysSincePractice) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AttentionList(int enrolled,
                                int flagged,
                                List<AttentionItem> students,
                                String note) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StudentNodeView(String nodeCode, String name, String chapter, double effective) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PracticeWindow(int days, int attempts, int correct, Double accuracyPct, String lastPracticeAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MistakeView(Long questionId, NodeRef primaryNode, List<NodeRef> misconceptionNodes,
                              String answeredAt, String scene) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanView(String status, int taskCount, int doneCount, boolean atRisk, String targetDate) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StudentProfile(String status,
                                 StudentRef student,
                                 Double avgEffective,
                                 Integer assessedNodes,
                                 Integer totalNodes,
                                 List<ChapterView> chapters,
                                 List<StudentNodeView> weakestNodes,
                                 PracticeWindow practice14d,
                                 List<MistakeView> recentMistakes,
                                 List<NodeRef> topMisconceptions,
                                 Integer reviewBacklog,
                                 PlanView plan,
                                 List<StudentRef> candidates,
                                 String note) {

        public static StudentProfile notFound(String note) {
            return new StudentProfile(STATUS_NOT_FOUND, null, null, null, null, null, null, null, null, null,
                    null, null, null, note);
        }

        public static StudentProfile ambiguous(List<StudentRef> candidates, String note) {
            return new StudentProfile(STATUS_AMBIGUOUS, null, null, null, null, null, null, null, null, null,
                    null, null, candidates, note);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeStudentView(long studentId, String name, Double effective, String level) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeMasteryResult(String status,
                                    NodeRef node,
                                    String chapter,
                                    Integer enrolled,
                                    StudentDistribution distribution,
                                    Double avgEffective,
                                    Integer notMasteredTotal,
                                    List<NodeStudentView> notMastered,
                                    List<NodeRef> candidates,
                                    String note) {

        public static NodeMasteryResult notFound(String note) {
            return new NodeMasteryResult(STATUS_NOT_FOUND, null, null, null, null, null, null, null, null, note);
        }

        public static NodeMasteryResult ambiguous(List<NodeRef> candidates, String note) {
            return new NodeMasteryResult(STATUS_AMBIGUOUS, null, null, null, null, null, null, null, candidates, note);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DistractorShare(String key, String text, int count, double sharePct, NodeRef misconceptionNode) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionStat(long questionId,
                               String stem,
                               int type,
                               NodeRef node,
                               int attempts,
                               int wrong,
                               double errorRatePct,
                               List<DistractorShare> distractors) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionErrorStats(int windowDays,
                                     int minSample,
                                     List<QuestionStat> questions,
                                     String note) {
    }

    public record DayStat(String date, int answers, int activeStudents, int companionConversations) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActivityTrend(int days, int enrolled, List<DayStat> daily, int totalAnswers, String note) {
    }
}
