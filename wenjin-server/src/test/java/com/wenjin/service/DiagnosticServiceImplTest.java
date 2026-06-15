package com.wenjin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PaperVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.impl.DiagnosticServiceImpl;
import com.wenjin.support.QuestionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DiagnosticServiceImpl 单元测试：Mockito 隔离数据库，覆盖
 *   A 正常组卷（30题/5章/target25 → 实际25题，覆盖≥4章），
 *   B 空题库，C 答案不泄露（Jackson 序列化验证）。
 */
@ExtendWith(MockitoExtension.class)
class DiagnosticServiceImplTest {

    @Mock QuestionMapper questionMapper;
    @Mock QuestionOptionMapper questionOptionMapper;
    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock KgNodeMapper kgNodeMapper;

    private static final Long COURSE_ID = 1L;

    /** 共 5 章，每章 6 题，总计 30 题 */
    private static final int CHAPTERS = 5;
    private static final int PER_CHAPTER = 6;
    private static final int TOTAL_QUESTIONS = CHAPTERS * PER_CHAPTER; // 30

    /** 测试题库（30道 APPROVED 题） */
    private List<Question> allQuestions;
    /** 题-知识点关联（每题一条 weight=1 主点） */
    private List<QuestionNode> allQuestionNodes;
    /** 知识点（每章一个节点，5个节点） */
    private List<KgNode> allKgNodes;

    @BeforeEach
    void buildFixture() {
        allQuestions = new ArrayList<>();
        allQuestionNodes = new ArrayList<>();
        allKgNodes = new ArrayList<>();

        // 5章，每章1个节点（奇数章 isKey=1，偶数章 isKey=0）
        String[] chapters = {"第一章", "第二章", "第三章", "第四章", "第五章"};
        for (int c = 0; c < CHAPTERS; c++) {
            long nodeId = (c + 1) * 100L;       // 100, 200, 300, 400, 500
            KgNode node = new KgNode();
            node.setId(nodeId);
            node.setCourseId(COURSE_ID);
            node.setNodeCode("KT0" + (c + 1));
            node.setName("知识点" + (c + 1));
            node.setChapter(chapters[c]);
            node.setDifficulty(3);
            node.setIsKey(c % 2 == 0 ? 1 : 0); // 第1/3/5章 isKey=1
            allKgNodes.add(node);

            // 每章6题
            for (int q = 0; q < PER_CHAPTER; q++) {
                long questionId = (long) (c * PER_CHAPTER + q + 1); // 1..30
                Question question = new Question();
                question.setId(questionId);
                question.setCourseId(COURSE_ID);
                question.setStem("题干-章" + (c + 1) + "-题" + (q + 1));
                question.setType(1);
                question.setDifficulty(3);
                question.setAnswer("A");         // 正确答案不应出现在 PaperQuestionVO
                question.setStatus(QuestionStatus.APPROVED);
                allQuestions.add(question);

                // weight=1 主点关联
                QuestionNode qn = new QuestionNode();
                qn.setId(questionId);
                qn.setQuestionId(questionId);
                qn.setNodeId(nodeId);
                qn.setWeight(1);
                allQuestionNodes.add(qn);
            }
        }
    }

    private DiagnosticServiceImpl impl() {
        DiagnosticServiceImpl impl = new DiagnosticServiceImpl(
                questionMapper, questionOptionMapper, questionNodeMapper, kgNodeMapper);
        ReflectionTestUtils.setField(impl, "paperSize", 25);
        return impl;
    }

    /** 两个选项（A 正确、B 错误）——答案信息绝不能下行 */
    private List<QuestionOption> twoOptions(Long questionId) {
        QuestionOption a = new QuestionOption();
        a.setId(questionId * 10 + 1);
        a.setQuestionId(questionId);
        a.setOptionKey("A");
        a.setOptionText("选项A文本");
        a.setIsCorrect(1);
        a.setPointNodeCode(null);

        QuestionOption b = new QuestionOption();
        b.setId(questionId * 10 + 2);
        b.setQuestionId(questionId);
        b.setOptionKey("B");
        b.setOptionText("选项B文本");
        b.setIsCorrect(0);
        b.setPointNodeCode("KT01");

        return List.of(a, b);
    }

    /**
     * 为列表中的所有题目生成选项（每题2个），用于批量选项查询的 mock stub。
     * 每个选项携带真实的 questionId，令 groupingBy 能正确分组。
     */
    private List<QuestionOption> optionsForAll(List<Question> questions) {
        List<QuestionOption> result = new ArrayList<>();
        for (Question q : questions) {
            result.addAll(twoOptions(q.getId()));
        }
        return result;
    }

    // ───────────────────── 用例 A：正常组卷 ─────────────────────

    @Test
    @DisplayName("A 正常组卷：30题/5章，target=25 → total=25，覆盖==5章")
    void normalPaperComposed() {
        when(questionMapper.selectList(any())).thenReturn(allQuestions);
        when(questionNodeMapper.selectList(any())).thenReturn(allQuestionNodes);
        when(kgNodeMapper.selectList(any())).thenReturn(allKgNodes);
        // 批量查询：返回所有题目的选项（每题有独立 questionId，groupingBy 能正确分组）
        when(questionOptionMapper.selectList(any()))
                .thenAnswer(inv -> optionsForAll(allQuestions));

        PaperVO paper = impl().composePaper(COURSE_ID);

        // 断言1：题目数在 [20,30]，且恰好 = target 25
        assertThat(paper.getTotal()).isBetween(20, 30);
        assertThat(paper.getQuestions()).hasSize(paper.getTotal());
        assertThat(paper.getTotal()).isEqualTo(25); // target = min(25, 30) = 25

        // 断言2：覆盖的章节 == 5（算法可证明覆盖全部5章）
        Set<String> coveredChapters = paper.getQuestions().stream()
                .map(PaperQuestionVO::getChapter)
                .collect(Collectors.toSet());
        assertThat(coveredChapters.size()).isEqualTo(5);

        // 所有题都属于该课程且有 chapter
        assertThat(paper.getCourseId()).isEqualTo(COURSE_ID);
        assertThat(paper.getQuestions()).allSatisfy(q ->
                assertThat(q.getChapter()).isNotBlank());

        // 断言3：每道题选项结构正确（key+text 均存在，且恰好2个选项）
        assertThat(paper.getQuestions()).allSatisfy(q -> {
            assertThat(q.getOptions()).hasSize(2);
            assertThat(q.getOptions()).allSatisfy(opt -> {
                assertThat(opt.getKey()).isNotBlank();
                assertThat(opt.getText()).isNotBlank();
            });
        });
    }

    // ───────────────────── 用例 B：空题库 ─────────────────────

    @Test
    @DisplayName("B 空题库：approved 为空 → total=0，questions 为空列表")
    void emptyPoolReturnsEmptyPaper() {
        when(questionMapper.selectList(any())).thenReturn(List.of());
        // 空题库时不应再调用 questionNodeMapper / kgNodeMapper（含隐性断言）

        PaperVO paper = impl().composePaper(COURSE_ID);

        assertThat(paper.getTotal()).isZero();
        assertThat(paper.getQuestions()).isEmpty();
        assertThat(paper.getCourseId()).isEqualTo(COURSE_ID);
    }

    // ───────────────────── 用例 C：答案不泄露 ─────────────────────

    @Test
    @DisplayName("C 答案不泄露：Jackson 序列化 PaperQuestionVO 不含 answer/isCorrect/correct/pointNodeCode")
    void noAnswerLeakage() throws Exception {
        when(questionMapper.selectList(any())).thenReturn(allQuestions);
        when(questionNodeMapper.selectList(any())).thenReturn(allQuestionNodes);
        when(kgNodeMapper.selectList(any())).thenReturn(allKgNodes);
        // 批量查询：返回所有题的真实选项（含 isCorrect / pointNodeCode，绝不能出现在 VO）
        when(questionOptionMapper.selectList(any()))
                .thenAnswer(inv -> optionsForAll(allQuestions));

        PaperVO paper = impl().composePaper(COURSE_ID);
        assertThat(paper.getQuestions()).isNotEmpty();

        ObjectMapper jackson = new ObjectMapper();
        // 取第一道题序列化为 JSON 字符串
        PaperQuestionVO firstQ = paper.getQuestions().get(0);
        String json = jackson.writeValueAsString(firstQ);

        // 关键断言：JSON 字符串中绝不出现任何答案字段名称
        assertThat(json).doesNotContain("answer");
        assertThat(json).doesNotContain("isCorrect");
        assertThat(json).doesNotContain("correct");
        assertThat(json).doesNotContain("pointNodeCode");

        // 补充：反射检查 PaperQuestionVO 声明字段不含答案
        List<String> declaredFieldNames = java.util.Arrays.stream(
                PaperQuestionVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toList());
        assertThat(declaredFieldNames).doesNotContain("answer", "isCorrect", "correct", "pointNodeCode");

        // 反射检查 OptionVO 声明字段
        List<String> optionFieldNames = java.util.Arrays.stream(
                PaperQuestionVO.OptionVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toList());
        assertThat(optionFieldNames).doesNotContain("answer", "isCorrect", "correct", "pointNodeCode");
    }

    // ───────────────────── 用例 D：章数 > target 分支 ─────────────────────

    @Test
    @DisplayName("D 章数>target：30题/30章，target=25 → total=25，覆盖25个不同章节")
    void manyChaptersExceedTarget() {
        // 30道题，每题独属一章（共30个不同章节）
        List<Question> questions = new ArrayList<>();
        List<QuestionNode> questionNodes = new ArrayList<>();
        List<KgNode> kgNodes = new ArrayList<>();

        for (int i = 0; i < 30; i++) {
            long questionId = i + 1L;
            long nodeId = (i + 1) * 100L;

            KgNode node = new KgNode();
            node.setId(nodeId);
            node.setCourseId(COURSE_ID);
            node.setNodeCode("KC" + (i + 1));
            node.setName("知识点" + (i + 1));
            node.setChapter("章节" + (i + 1));
            node.setDifficulty(3);
            node.setIsKey(0);
            kgNodes.add(node);

            Question q = new Question();
            q.setId(questionId);
            q.setCourseId(COURSE_ID);
            q.setStem("题干-" + (i + 1));
            q.setType(1);
            q.setDifficulty(3);
            q.setAnswer("A");
            q.setStatus(QuestionStatus.APPROVED);
            questions.add(q);

            QuestionNode qn = new QuestionNode();
            qn.setId(questionId);
            qn.setQuestionId(questionId);
            qn.setNodeId(nodeId);
            qn.setWeight(1);
            questionNodes.add(qn);
        }

        when(questionMapper.selectList(any())).thenReturn(questions);
        when(questionNodeMapper.selectList(any())).thenReturn(questionNodes);
        when(kgNodeMapper.selectList(any())).thenReturn(kgNodes);
        when(questionOptionMapper.selectList(any()))
                .thenAnswer(inv -> optionsForAll(questions));

        PaperVO paper = impl().composePaper(COURSE_ID);

        // 断言：恰好选出 25 题，覆盖 25 个不同章节
        assertThat(paper.getTotal()).isEqualTo(25);
        assertThat(paper.getQuestions()).hasSize(25);

        Set<String> coveredChapters = paper.getQuestions().stream()
                .map(PaperQuestionVO::getChapter)
                .collect(Collectors.toSet());
        assertThat(coveredChapters).hasSize(25);
    }

    // ───────────────────── 用例 E：cap + fill-up 路径 ─────────────────────

    @Test
    @DisplayName("E cap+fill-up：A章1题，B/C/D/E各10题，target=25 → total=25，全5章覆盖，A章题必入选")
    void capAndFillUpPath() {
        // 章 A: 1 题；章 B,C,D,E: 各 10 题；共 41 题
        // 比例分配结果：A=1, B=C=D=E=6，合计 25（A恰好触发 cap）
        List<Question> questions = new ArrayList<>();
        List<QuestionNode> questionNodes = new ArrayList<>();
        List<KgNode> kgNodes = new ArrayList<>();

        String[] chapterNames = {"章A", "章B", "章C", "章D", "章E"};
        int[] sizes = {1, 10, 10, 10, 10};
        long questionIdCounter = 1L;

        for (int c = 0; c < chapterNames.length; c++) {
            long nodeId = (c + 1) * 1000L;

            KgNode node = new KgNode();
            node.setId(nodeId);
            node.setCourseId(COURSE_ID);
            node.setNodeCode("KE" + (c + 1));
            node.setName("节点" + chapterNames[c]);
            node.setChapter(chapterNames[c]);
            node.setDifficulty(3);
            node.setIsKey(0);
            kgNodes.add(node);

            for (int i = 0; i < sizes[c]; i++) {
                long qId = questionIdCounter++;
                Question q = new Question();
                q.setId(qId);
                q.setCourseId(COURSE_ID);
                q.setStem("题干-" + chapterNames[c] + "-" + i);
                q.setType(1);
                q.setDifficulty(3);
                q.setAnswer("A");
                q.setStatus(QuestionStatus.APPROVED);
                questions.add(q);

                QuestionNode qn = new QuestionNode();
                qn.setId(qId);
                qn.setQuestionId(qId);
                qn.setNodeId(nodeId);
                qn.setWeight(1);
                questionNodes.add(qn);
            }
        }

        when(questionMapper.selectList(any())).thenReturn(questions);
        when(questionNodeMapper.selectList(any())).thenReturn(questionNodes);
        when(kgNodeMapper.selectList(any())).thenReturn(kgNodes);
        when(questionOptionMapper.selectList(any()))
                .thenAnswer(inv -> optionsForAll(questions));

        DiagnosticServiceImpl svc = new DiagnosticServiceImpl(
                questionMapper, questionOptionMapper, questionNodeMapper, kgNodeMapper);
        ReflectionTestUtils.setField(svc, "paperSize", 25);

        PaperVO paper = svc.composePaper(COURSE_ID);

        // 断言1：恰好 25 题
        assertThat(paper.getTotal()).isEqualTo(25);
        assertThat(paper.getQuestions()).hasSize(25);

        // 断言2：全 5 章均有题目
        Set<String> coveredChapters = paper.getQuestions().stream()
                .map(PaperQuestionVO::getChapter)
                .collect(Collectors.toSet());
        assertThat(coveredChapters).containsExactlyInAnyOrder("章A", "章B", "章C", "章D", "章E");

        // 断言3：章A 的唯一一题（questionId=1）必须入选
        Set<Long> selectedIds = paper.getQuestions().stream()
                .map(PaperQuestionVO::getQuestionId)
                .collect(Collectors.toSet());
        assertThat(selectedIds).contains(1L); // 章A的唯一题 id=1

        // 断言4：章A 恰好 1 题（cap生效）
        long countA = paper.getQuestions().stream()
                .filter(q -> "章A".equals(q.getChapter()))
                .count();
        assertThat(countA).isEqualTo(1);
    }
}
