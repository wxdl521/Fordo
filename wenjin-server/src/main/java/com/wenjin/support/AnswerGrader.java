package com.wenjin.support;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 纯函数判分组件：无 Spring 依赖，可直接 new 单测。
 * 供诊断提交（DiagnosticServiceImpl）和练习提交（M1 后续任务）共用，避免重复判分逻辑。
 *
 * <p>支持题型：
 * <ul>
 *   <li>单选（{@link QuestionType#SINGLE}）：一个正确选项，精确匹配。</li>
 *   <li>多选（{@link QuestionType#MULTI}）：多个正确选项，顺序无关集合比较。</li>
 *   <li>判断（{@link QuestionType#TRUE_FALSE}）：同单选逻辑。</li>
 *   <li>简答（{@link QuestionType#SHORT_ANSWER}）：跳过，correct=null，不进对错统计。</li>
 * </ul>
 */
public class AnswerGrader {

    /**
     * 单题判分结果。
     *
     * @param correct        是否答对；{@code null} 表示该题不参与对错统计（如简答题）。
     * @param wrongChosenKeys 学生选择了但不在正确集合中的选项标识（用于 distractor 归因）。
     */
    public record GradeResult(Boolean correct, Set<String> wrongChosenKeys) {

        /** 跳过判分（简答题专用）。 */
        public static GradeResult skipped() {
            return new GradeResult(null, Set.of());
        }

        /** 是否参与对错统计（即非简答题）。 */
        public boolean isGradeable() {
            return correct != null;
        }
    }

    /**
     * 对单道题判分。
     *
     * @param questionType  题型（见 {@link QuestionType}）
     * @param studentAnswer 学生提交的选项标识：单选/判断为单字母（如 "A"），
     *                      多选为逗号分隔串（如 "C,A"），简答为任意文本或 null。
     * @param correctKeys   题目所有 is_correct=1 选项的标识集合；空集表示题库无正确项。
     * @return 判分结果（非 null）
     */
    public GradeResult grade(int questionType, String studentAnswer, Set<String> correctKeys) {
        if (questionType == QuestionType.SHORT_ANSWER) {
            return GradeResult.skipped();
        }
        if (questionType == QuestionType.MULTI) {
            return gradeMultiChoice(studentAnswer, correctKeys);
        }
        // 单选（SINGLE）和判断（TRUE_FALSE）逻辑相同
        return gradeSingleChoice(studentAnswer, correctKeys);
    }

    // ── 私有判分逻辑 ──────────────────────────────────────────────────────────

    private GradeResult gradeSingleChoice(String studentAnswer, Set<String> correctKeys) {
        if (studentAnswer == null || correctKeys.isEmpty()) {
            // 无答案或题库无正确项 → 判错；无错选 key 可归因
            return new GradeResult(false, Set.of());
        }
        boolean correct = correctKeys.contains(studentAnswer);
        Set<String> wrongChosen = correct ? Set.of() : Set.of(studentAnswer);
        return new GradeResult(correct, wrongChosen);
    }

    private GradeResult gradeMultiChoice(String studentAnswer, Set<String> correctKeys) {
        if (studentAnswer == null || studentAnswer.isBlank()) {
            return new GradeResult(false, Set.of());
        }
        Set<String> chosen = Arrays.stream(studentAnswer.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        boolean correct = chosen.equals(correctKeys);
        // 错选 = 学生选了但不在正确集合中的选项（漏选不计入错选，等待 distractor 归因）
        Set<String> wrongChosen = chosen.stream()
                .filter(k -> !correctKeys.contains(k))
                .collect(Collectors.toUnmodifiableSet());
        return new GradeResult(correct, wrongChosen);
    }
}
