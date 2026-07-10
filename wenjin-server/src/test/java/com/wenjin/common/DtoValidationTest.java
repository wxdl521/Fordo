package com.wenjin.common;

import com.wenjin.dto.AdaptiveAnswerRequest;
import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.dto.LoginRequest;
import com.wenjin.dto.NodeUpsertRequest;
import com.wenjin.dto.PracticeStartRequest;
import com.wenjin.dto.PracticeSubmitRequest;
import com.wenjin.dto.QuestionReviewRequest;
import com.wenjin.dto.RegisterRequest;
import com.wenjin.dto.SubmitRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation 约束单元测试：覆盖注册/登录、ID 正数、难度、批量与分页边界等。
 */
class DtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        if (factory != null) {
            factory.close();
        }
    }

    private static Set<String> props(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    // ── username / password / realName ──

    @Test
    @DisplayName("username 3..64：过短/过长失败，合法通过")
    void usernameBounds() {
        RegisterRequest shortName = validRegister();
        shortName.setUsername("ab");
        assertThat(props(validator.validate(shortName))).contains("username");

        RegisterRequest longName = validRegister();
        longName.setUsername("x".repeat(65));
        assertThat(props(validator.validate(longName))).contains("username");

        RegisterRequest ok = validRegister();
        ok.setUsername("abc");
        assertThat(validator.validate(ok)).isEmpty();

        LoginRequest loginShort = new LoginRequest();
        loginShort.setUsername("ab");
        loginShort.setPassword("any");
        assertThat(props(validator.validate(loginShort))).contains("username");
    }

    @Test
    @DisplayName("password 注册 8..72；登录仅上限 72")
    void passwordBounds() {
        RegisterRequest shortPwd = validRegister();
        shortPwd.setPassword("1234567");
        assertThat(props(validator.validate(shortPwd))).contains("password");

        RegisterRequest longPwd = validRegister();
        longPwd.setPassword("p".repeat(73));
        assertThat(props(validator.validate(longPwd))).contains("password");

        RegisterRequest ok = validRegister();
        ok.setPassword("12345678");
        assertThat(validator.validate(ok)).isEmpty();

        LoginRequest loginLong = new LoginRequest();
        loginLong.setUsername("alice");
        loginLong.setPassword("p".repeat(73));
        assertThat(props(validator.validate(loginLong))).contains("password");

        // 登录兼容历史短口令
        LoginRequest loginShort = new LoginRequest();
        loginShort.setUsername("alice");
        loginShort.setPassword("demo");
        assertThat(validator.validate(loginShort)).isEmpty();
    }

    @Test
    @DisplayName("realName 0..64：null/空串允许，超长失败")
    void realNameBounds() {
        RegisterRequest nullName = validRegister();
        nullName.setRealName(null);
        assertThat(validator.validate(nullName)).isEmpty();

        RegisterRequest empty = validRegister();
        empty.setRealName("");
        assertThat(validator.validate(empty)).isEmpty();

        RegisterRequest tooLong = validRegister();
        tooLong.setRealName("名".repeat(65));
        assertThat(props(validator.validate(tooLong))).contains("realName");
    }

    // ── courseId / nodeId / questionId positive ──

    @Test
    @DisplayName("courseId/nodeId/questionId 须为正数")
    void idsMustBePositive() {
        PracticeStartRequest start = new PracticeStartRequest();
        start.setCourseId(0L);
        start.setNodeId(-1L);
        assertThat(props(validator.validate(start))).contains("courseId", "nodeId");

        PracticeSubmitRequest.AnswerItem item = new PracticeSubmitRequest.AnswerItem();
        item.setQuestionId(0L);
        PracticeSubmitRequest submit = new PracticeSubmitRequest();
        submit.setAnswers(List.of(item));
        assertThat(props(validator.validate(submit))).anyMatch(p -> p.contains("questionId"));
    }

    // ── difficulty 1..5 ──

    @Test
    @DisplayName("difficulty 1..5")
    void difficultyBounds() {
        NodeUpsertRequest low = new NodeUpsertRequest();
        low.setName("n");
        low.setDifficulty(0);
        assertThat(props(validator.validate(low))).contains("difficulty");

        NodeUpsertRequest high = new NodeUpsertRequest();
        high.setName("n");
        high.setDifficulty(6);
        assertThat(props(validator.validate(high))).contains("difficulty");

        NodeUpsertRequest ok = new NodeUpsertRequest();
        ok.setName("n");
        ok.setDifficulty(3);
        assertThat(validator.validate(ok)).isEmpty();
    }

    // ── batch review 1..500 ──

    @Test
    @DisplayName("批量审核 ids 1..500")
    void batchReviewIdsBounds() {
        QuestionReviewRequest empty = new QuestionReviewRequest();
        empty.setIds(List.of());
        empty.setAction("pass");
        assertThat(props(validator.validate(empty))).contains("ids");

        QuestionReviewRequest tooMany = new QuestionReviewRequest();
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 501; i++) {
            ids.add(i);
        }
        tooMany.setIds(ids);
        tooMany.setAction("pass");
        assertThat(props(validator.validate(tooMany))).contains("ids");

        QuestionReviewRequest ok = new QuestionReviewRequest();
        ok.setIds(List.of(1L));
        ok.setAction("pass");
        assertThat(validator.validate(ok)).isEmpty();
    }

    // ── answers 1..100 ──

    @Test
    @DisplayName("answers 数量 1..100 且嵌套 @Valid")
    void answersSizeAndNestedValid() {
        PracticeSubmitRequest empty = new PracticeSubmitRequest();
        empty.setAnswers(Collections.emptyList());
        assertThat(props(validator.validate(empty))).contains("answers");

        PracticeSubmitRequest tooMany = new PracticeSubmitRequest();
        List<PracticeSubmitRequest.AnswerItem> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            PracticeSubmitRequest.AnswerItem a = new PracticeSubmitRequest.AnswerItem();
            a.setQuestionId((long) (i + 1));
            many.add(a);
        }
        tooMany.setAnswers(many);
        assertThat(props(validator.validate(tooMany))).contains("answers");

        AdaptiveAnswerRequest adaptiveEmpty = new AdaptiveAnswerRequest();
        adaptiveEmpty.setAnswers(List.of());
        assertThat(props(validator.validate(adaptiveEmpty))).contains("answers");

        SubmitRequest submitEmpty = new SubmitRequest();
        submitEmpty.setAnswers(List.of());
        assertThat(props(validator.validate(submitEmpty))).contains("answers");

        PracticeSubmitRequest ok = new PracticeSubmitRequest();
        PracticeSubmitRequest.AnswerItem a = new PracticeSubmitRequest.AnswerItem();
        a.setQuestionId(1L);
        ok.setAnswers(List.of(a));
        assertThat(validator.validate(ok)).isEmpty();
    }

    // ── companion message 1..4000 ──

    @Test
    @DisplayName("companion message 1..4000")
    void companionMessageBounds() {
        CompanionChatRequest empty = new CompanionChatRequest();
        empty.setCourseId(1L);
        empty.setMessage("");
        assertThat(props(validator.validate(empty))).contains("message");

        CompanionChatRequest tooLong = new CompanionChatRequest();
        tooLong.setCourseId(1L);
        tooLong.setMessage("m".repeat(4001));
        assertThat(props(validator.validate(tooLong))).contains("message");

        CompanionChatRequest ok = new CompanionChatRequest();
        ok.setCourseId(1L);
        ok.setMessage("hello");
        assertThat(validator.validate(ok)).isEmpty();
    }

    // ── practice size min 1 ──

    @Test
    @DisplayName("practice size 须 >= 1（可空）")
    void practiceSizeMin() {
        PracticeStartRequest zero = validPracticeStart();
        zero.setSize(0);
        assertThat(props(validator.validate(zero))).contains("size");

        PracticeStartRequest nullSize = validPracticeStart();
        nullSize.setSize(null);
        assertThat(validator.validate(nullSize)).isEmpty();
    }

    private static RegisterRequest validRegister() {
        RegisterRequest r = new RegisterRequest();
        r.setUsername("student01");
        r.setPassword("password1");
        r.setRealName("张三");
        return r;
    }

    private static PracticeStartRequest validPracticeStart() {
        PracticeStartRequest r = new PracticeStartRequest();
        r.setCourseId(1L);
        r.setNodeId(2L);
        return r;
    }
}
