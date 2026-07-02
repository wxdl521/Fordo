package com.wenjin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AnswerGrader 单元测试（纯函数，直接 new）。
 * TDD 先写：
 *   单选正确/错误、多选顺序无关、多选多选/漏选、判断题、简答题跳过。
 */
class AnswerGraderTest {

    private AnswerGrader grader;

    @BeforeEach
    void setUp() {
        grader = new AnswerGrader();
    }

    // ── 单选题 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("单选-正确：学生选 A，正确答案 {A} → correct=true，无错选")
    void singleChoiceCorrect() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.SINGLE, "A", Set.of("A"));
        assertThat(result.correct()).isTrue();
        assertThat(result.wrongChosenKeys()).isEmpty();
    }

    @Test
    @DisplayName("单选-错误：学生选 B，正确答案 {A} → correct=false，wrongKeys={B}")
    void singleChoiceWrong() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.SINGLE, "B", Set.of("A"));
        assertThat(result.correct()).isFalse();
        assertThat(result.wrongChosenKeys()).containsExactly("B");
    }

    @Test
    @DisplayName("单选-无正确选项：空 correctKeys → correct=false")
    void singleChoiceNoCorrectOption() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.SINGLE, "A", Set.of());
        assertThat(result.correct()).isFalse();
    }

    @Test
    @DisplayName("单选-未作答(null)：→ correct=false，无错选")
    void singleChoiceNullAnswer() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.SINGLE, null, Set.of("A"));
        assertThat(result.correct()).isFalse();
        assertThat(result.wrongChosenKeys()).isEmpty();
    }

    // ── 多选题 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("多选-顺序无关：学生选 C,A，正确答案 {A,C} → correct=true，无错选")
    void multiChoiceCorrect_orderInsensitive() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.MULTI, "C,A", Set.of("A", "C"));
        assertThat(result.correct()).isTrue();
        assertThat(result.wrongChosenKeys()).isEmpty();
    }

    @Test
    @DisplayName("多选-顺序无关：学生选 A,C，正确答案 {A,C} → correct=true")
    void multiChoiceCorrect_sameOrder() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.MULTI, "A,C", Set.of("A", "C"));
        assertThat(result.correct()).isTrue();
        assertThat(result.wrongChosenKeys()).isEmpty();
    }

    @Test
    @DisplayName("多选-多选(错选B)：学生选 A,B,C，正确答案 {A,C} → correct=false，wrongKeys={B}")
    void multiChoiceWrongOverSelection() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.MULTI, "A,B,C", Set.of("A", "C"));
        assertThat(result.correct()).isFalse();
        assertThat(result.wrongChosenKeys()).containsExactlyInAnyOrder("B");
    }

    @Test
    @DisplayName("多选-漏选：学生只选 A，正确答案 {A,C} → correct=false，wrongKeys 为空（仅漏选无错选）")
    void multiChoiceWrongUnderSelection() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.MULTI, "A", Set.of("A", "C"));
        assertThat(result.correct()).isFalse();
        assertThat(result.wrongChosenKeys()).isEmpty();
    }

    @Test
    @DisplayName("多选-全错：学生选 B,D，正确答案 {A,C} → correct=false，wrongKeys={B,D}")
    void multiChoiceAllWrong() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.MULTI, "B,D", Set.of("A", "C"));
        assertThat(result.correct()).isFalse();
        assertThat(result.wrongChosenKeys()).containsExactlyInAnyOrder("B", "D");
    }

    @Test
    @DisplayName("多选-未作答(null)：→ correct=false，wrongKeys 为空")
    void multiChoiceNullAnswer() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.MULTI, null, Set.of("A", "C"));
        assertThat(result.correct()).isFalse();
        assertThat(result.wrongChosenKeys()).isEmpty();
    }

    // ── 判断题 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("判断-正确：学生选 A（正确），正确答案 {A} → correct=true")
    void trueFalseCorrect() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.TRUE_FALSE, "A", Set.of("A"));
        assertThat(result.correct()).isTrue();
        assertThat(result.wrongChosenKeys()).isEmpty();
    }

    @Test
    @DisplayName("判断-错误：学生选 B（错误），正确答案 {A} → correct=false，wrongKeys={B}")
    void trueFalseWrong() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.TRUE_FALSE, "B", Set.of("A"));
        assertThat(result.correct()).isFalse();
        assertThat(result.wrongChosenKeys()).containsExactly("B");
    }

    // ── 简答题（跳过） ────────────────────────────────────────────────────────

    @Test
    @DisplayName("简答-跳过：correct=null（不进对错统计），wrongKeys 为空")
    void shortAnswerSkipped() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.SHORT_ANSWER, "任意文本", Set.of());
        assertThat(result.correct()).isNull();
        assertThat(result.wrongChosenKeys()).isEmpty();
    }

    @Test
    @DisplayName("简答-跳过(即使有正确选项)：correct=null（绝不判分）")
    void shortAnswerSkippedEvenWithCorrectKeys() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.SHORT_ANSWER, "A", Set.of("A"));
        assertThat(result.correct()).isNull();
    }

    @Test
    @DisplayName("简答-跳过(null答案)：correct=null")
    void shortAnswerNullAnswerSkipped() {
        AnswerGrader.GradeResult result = grader.grade(QuestionType.SHORT_ANSWER, null, Set.of());
        assertThat(result.correct()).isNull();
    }
}
