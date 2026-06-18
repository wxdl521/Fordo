package com.wenjin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.QuestionBankCleanAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.dto.ImportBankResult;
import com.wenjin.dto.QuestionBankFile;
import com.wenjin.entity.Course;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.GraphQueryService;
import com.wenjin.support.QuestionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QuestionBankImportServiceImpl 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class QuestionBankImportServiceImplTest {

    @Mock QuestionBankCleanAiClient aiClient;
    @Mock GraphQueryService graphQueryService;
    @Mock KgNodeMapper kgNodeMapper;
    @Mock QuestionMapper questionMapper;
    @Mock QuestionOptionMapper questionOptionMapper;
    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock CourseMapper courseMapper;

    private static final Long COURSE_ID = 1L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private QuestionBankImportServiceImpl service() {
        return new QuestionBankImportServiceImpl(
                aiClient, graphQueryService, kgNodeMapper,
                questionMapper, questionOptionMapper, questionNodeMapper,
                courseMapper, OBJECT_MAPPER);
    }

    /** 让 questionMapper.insert 模拟自增主键。 */
    private void stubInsertAutoId() {
        AtomicLong seq = new AtomicLong(1000L);
        when(questionMapper.insert(any(Question.class))).thenAnswer(inv -> {
            inv.getArgument(0, Question.class).setId(seq.incrementAndGet());
            return 1;
        });
    }

    // ──────────────── 用例 A：importFromJson 全新导入 ────────────────

    @Test
    @DisplayName("A importFromJson：2 题全部落库，status=已通过、source=1、type=1")
    void importFromJsonAllImported() {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(course(COURSE_ID));
        when(graphQueryService.codeToId(COURSE_ID))
                .thenReturn(Map.of("KT07", 70L, "KT05", 50L));
        stubInsertAutoId();
        when(questionMapper.selectList(any())).thenReturn(new ArrayList<>());

        QuestionBankFile bank = bankFile(
                bankQuestion("瀑布模型特点？", "KT07"),
                bankQuestion("迭代模型特点？", "KT05"));

        ImportBankResult result = service().importFromJson(COURSE_ID, bank);

        assertThat(result.getImported()).isEqualTo(2);
        assertThat(result.getSkipped()).isZero();

        // question 落库 2 次：status=已通过、source=1、type=1
        ArgumentCaptor<Question> qCaptor = ArgumentCaptor.forClass(Question.class);
        verify(questionMapper, times(2)).insert(qCaptor.capture());
        assertThat(qCaptor.getAllValues()).allSatisfy(q -> {
            assertThat(q.getStatus()).isEqualTo(QuestionStatus.APPROVED);
            assertThat(q.getSource()).isEqualTo(1);
            assertThat(q.getType()).isEqualTo(1);
            assertThat(q.getAnswer()).isEqualTo("A");
        });

        // question_node：每题一条主点 weight=1
        ArgumentCaptor<QuestionNode> nCaptor = ArgumentCaptor.forClass(QuestionNode.class);
        verify(questionNodeMapper, times(2)).insert(nCaptor.capture());
        assertThat(nCaptor.getAllValues()).allMatch(n -> n.getWeight() == 1);

        // 选项落库：每题 2 个选项
        verify(questionOptionMapper, times(4)).insert(any(QuestionOption.class));
    }

    // ──────────────── 用例 B：importFromJson 一题去重 ────────────────

    @Test
    @DisplayName("B importFromJson：首题题干已存在被跳过，imported==1、skipped==1")
    void importFromJsonOneSkipped() {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(course(COURSE_ID));
        when(graphQueryService.codeToId(COURSE_ID))
                .thenReturn(Map.of("KT07", 70L, "KT05", 50L));
        stubInsertAutoId();
        // 首题查到已存在 → skip；次题查不到 → import
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(new Question()))
                .thenReturn(new ArrayList<>());

        QuestionBankFile bank = bankFile(
                bankQuestion("已存在的题干？", "KT07"),
                bankQuestion("全新的题干？", "KT05"));

        ImportBankResult result = service().importFromJson(COURSE_ID, bank);

        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getSkipped()).isEqualTo(1);
        verify(questionMapper, times(1)).insert(any(Question.class));
    }

    // ──────────────── 用例 C：courseId 为空抛异常 ────────────────

    @Test
    @DisplayName("C importFromJson：courseId 为空抛 BAD_REQUEST")
    void importFromJsonNullCourseId() {
        QuestionBankFile bank = bankFile(bankQuestion("题干？", "KT07"));
        assertThatThrownBy(() -> service().importFromJson(null, bank))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("courseId");
    }

    // ──────────────── 用例 D：课程不存在抛异常 ────────────────

    @Test
    @DisplayName("D persistBank：课程不存在抛 NOT_FOUND")
    void importFromJsonCourseNotFound() {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(null);
        QuestionBankFile bank = bankFile(bankQuestion("题干？", "KT07"));

        assertThatThrownBy(() -> service().importFromJson(COURSE_ID, bank))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("课程不存在");
    }

    // ──────────────── 用例 E：importFromJson aiCleaned=false ────────────────

    @Test
    @DisplayName("E importFromJson：aiCleaned=false（JSON 导入不经过 AI）")
    void importFromJsonAiCleanedFalse() {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(course(COURSE_ID));
        when(graphQueryService.codeToId(COURSE_ID))
                .thenReturn(Map.of("KT07", 70L));
        stubInsertAutoId();
        when(questionMapper.selectList(any())).thenReturn(new ArrayList<>());

        QuestionBankFile bank = bankFile(bankQuestion("测试题？", "KT07"));
        ImportBankResult result = service().importFromJson(COURSE_ID, bank);

        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.isAiCleaned()).isFalse();
    }

    // ──────────────── 用例 F：ruleBasedClean 规则清洗 ────────────────

    @Test
    @DisplayName("F ruleBasedClean：difficulty 越界修正为 3，analysis 缺失补占位符，选项 key 大写")
    void ruleBasedCleanNormalizes() {
        QuestionBankFile.BankQuestion raw = new QuestionBankFile.BankQuestion();
        raw.setStem("  测试题干  ");
        raw.setNodeCode("KT07");
        raw.setDifficulty(9); // 越界
        raw.setAnalysis(null); // 缺失
        raw.setOptions(new ArrayList<>(List.of(
                bankOption("a", "  选项A  ", true),
                bankOption("b", "选项B", false))));

        QuestionBankFile cleaned = service().ruleBasedClean(List.of(raw));

        assertThat(cleaned.getQuestions()).hasSize(1);
        QuestionBankFile.BankQuestion q = cleaned.getQuestions().get(0);
        assertThat(q.getDifficulty()).isEqualTo(3);
        assertThat(q.getAnalysis()).isEqualTo("暂无解析");
        assertThat(q.getOptions().get(0).getKey()).isEqualTo("A");
        assertThat(q.getOptions().get(1).getKey()).isEqualTo("B");
        assertThat(q.getOptions().get(0).getText()).isEqualTo("选项A");
    }

    // ──────────────── 用例 G：ruleBasedClean 选项不足跳过 ────────────────

    @Test
    @DisplayName("G ruleBasedClean：选项不足 2 个的题目被跳过")
    void ruleBasedCleanSkipsInsufficientOptions() {
        QuestionBankFile.BankQuestion raw = new QuestionBankFile.BankQuestion();
        raw.setStem("只有1个选项");
        raw.setNodeCode("KT07");
        raw.setDifficulty(3);
        raw.setAnalysis("解析");
        raw.setOptions(new ArrayList<>(List.of(
                bankOption("A", "唯一选项", true))));

        QuestionBankFile cleaned = service().ruleBasedClean(List.of(raw));

        assertThat(cleaned.getQuestions()).isEmpty();
    }

    // ──────────────── 用例 H：ruleBasedClean 无正确项跳过 ────────────────

    @Test
    @DisplayName("H ruleBasedClean：无正确项的题目被跳过")
    void ruleBasedCleanSkipsNoCorrect() {
        QuestionBankFile.BankQuestion raw = new QuestionBankFile.BankQuestion();
        raw.setStem("无正确项");
        raw.setNodeCode("KT07");
        raw.setDifficulty(3);
        raw.setAnalysis("解析");
        raw.setOptions(new ArrayList<>(List.of(
                bankOption("A", "选项A", false),
                bankOption("B", "选项B", false))));

        QuestionBankFile cleaned = service().ruleBasedClean(List.of(raw));

        assertThat(cleaned.getQuestions()).isEmpty();
    }

    // ───────────────────────── 测试辅助 ─────────────────────────

    private Course course(Long id) {
        Course c = new Course();
        c.setId(id);
        c.setCode("52015CC4B4");
        c.setName("软件工程");
        return c;
    }

    private QuestionBankFile bankFile(QuestionBankFile.BankQuestion... questions) {
        QuestionBankFile f = new QuestionBankFile();
        f.setCourseCode("52015CC4B4");
        f.setQuestions(new ArrayList<>(List.of(questions)));
        return f;
    }

    /** 一道题库题：2 选项（A 正确、B 错误），主点 nodeCode，难度 2。 */
    private QuestionBankFile.BankQuestion bankQuestion(String stem, String nodeCode) {
        QuestionBankFile.BankQuestion q = new QuestionBankFile.BankQuestion();
        q.setStem(stem);
        q.setNodeCode(nodeCode);
        q.setChapter("软件工程概述");
        q.setDifficulty(2);
        q.setAnalysis("解析");
        q.setOptions(new ArrayList<>(List.of(
                bankOption("A", "正确项", true),
                bankOption("B", "干扰项", false))));
        return q;
    }

    private QuestionBankFile.BankOption bankOption(String key, String text, boolean correct) {
        QuestionBankFile.BankOption o = new QuestionBankFile.BankOption();
        o.setKey(key);
        o.setText(text);
        o.setCorrect(correct);
        return o;
    }
}
