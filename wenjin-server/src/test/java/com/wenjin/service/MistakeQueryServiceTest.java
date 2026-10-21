package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.MisconceptionSummary;
import com.wenjin.dto.MistakeReport;
import com.wenjin.dto.MistakeVO;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.PracticeSession;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.PracticeSessionMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.support.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MistakeQueryServiceTest {

    private static final long STUDENT = 7L;
    private static final long COURSE = 3L;

    @Mock AnswerRecordMapper answerRecordMapper;
    @Mock QuestionMapper questionMapper;
    @Mock QuestionOptionMapper optionMapper;
    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock KgNodeMapper nodeMapper;
    @Mock PracticeSessionMapper practiceSessionMapper;

    private MistakeQueryService service;

    private final List<AnswerRecord> records = new ArrayList<>();
    private final List<Question> questions = new ArrayList<>();
    private final List<QuestionOption> options = new ArrayList<>();
    private final List<QuestionNode> questionNodes = new ArrayList<>();
    private final List<KgNode> nodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new MistakeQueryService(answerRecordMapper, questionMapper, optionMapper,
                questionNodeMapper, nodeMapper, practiceSessionMapper);
        when(answerRecordMapper.selectList(any())).thenAnswer(inv -> List.copyOf(records));
        when(questionMapper.selectBatchIds(anyCollection())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return questions.stream().filter(q -> ids.contains(q.getId())).toList();
        });
        when(optionMapper.selectList(any())).thenAnswer(inv -> List.copyOf(options));
        when(questionNodeMapper.selectList(any())).thenAnswer(inv -> List.copyOf(questionNodes));
        when(nodeMapper.selectBatchIds(anyCollection())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return nodes.stream().filter(n -> ids.contains(n.getId())).toList();
        });
        // by-code lookups: return active nodes of the course (service filters by requested codes)
        when(nodeMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> nodes.stream()
                .filter(n -> n.getArchivedAt() == null)
                .toList());

        node(100L, "SE-3.2", "用例图", false);
        node(101L, "SE-2.1", "需求获取", false);
        node(102L, "SE-1.1", "软件工程概述", false);
        node(103L, "SE-OLD", "已归档节点", true);
    }

    @Test
    @DisplayName("nodeCode 过滤下推到 SQL（参数化子查询），扫描上限内不会漏掉该节点更早的错题")
    void nodeFilter_pushedDownToSqlAsBoundParameters() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                AnswerRecord.class);
        question(1L, QuestionType.SINGLE, "题", "A", 100L);
        option(1L, "A", "对", true, null);
        option(1L, "B", "错", false, "SE-2.1");
        answer(11L, 1L, "B", 2, 55L, LocalDateTime.of(2026, 10, 20, 9, 0));

        service.report(STUDENT, COURSE, "SE-2.1' OR 1=1 --", null, 3);
        service.report(STUDENT, COURSE, "SE-2.1", null, 3);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AnswerRecord>> cap =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(answerRecordMapper, org.mockito.Mockito.times(2)).selectList(cap.capture());
        for (var w : cap.getAllValues()) {
            String sql = w.getSqlSegment();
            assertThat(sql).contains("question_option").contains("point_node_code").contains("question_node");
            assertThat(sql).doesNotContain("SE-2.1");
        }
        assertThat(cap.getAllValues().get(1).getParamNameValuePairs()).containsValue("SE-2.1");
        assertThat(cap.getAllValues().get(1).getParamNameValuePairs()).containsValue(COURSE);
    }

    @Test
    @DisplayName("无 nodeCode：不加节点子查询")
    void noNodeFilter_noSubquery() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                AnswerRecord.class);
        answer(11L, 1L, "B", 2, 55L, LocalDateTime.of(2026, 10, 20, 9, 0));
        service.report(STUDENT, COURSE, null, null, 3);
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AnswerRecord>> cap =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(answerRecordMapper).selectList(cap.capture());
        assertThat(cap.getValue().getSqlSegment()).doesNotContain("question_option");
    }

    @Test
    @DisplayName("单选错题：所选/正确选项带文本，误区节点来自所选干扰项的 point_node_code，题干截断")
    void singleChoice_mapsChosenCorrectAndMisconception() {
        question(1L, QuestionType.SINGLE, "很长的题干".repeat(30), "A", 100L);
        option(1L, "A", "参与者与用例", true, null);
        option(1L, "B", "访谈记录", false, "SE-2.1");
        answer(11L, 1L, "B", 2, 55L, LocalDateTime.of(2026, 10, 20, 9, 0));

        MistakeReport report = service.report(STUDENT, COURSE, null, null, 3);

        assertThat(report.mistakes()).hasSize(1);
        MistakeVO m = report.mistakes().get(0);
        assertThat(m.questionId()).isEqualTo(1L);
        assertThat(m.stem().length()).isLessThanOrEqualTo(MistakeQueryService.STEM_MAX_CHARS + 1);
        assertThat(m.stem()).endsWith("…");
        assertThat(m.chosen()).containsExactly(new MistakeVO.OptionRef("B", "访谈记录"));
        assertThat(m.correct()).containsExactly(new MistakeVO.OptionRef("A", "参与者与用例"));
        assertThat(m.misconceptionNodes()).containsExactly(new MistakeVO.NodeRef("SE-2.1", "需求获取"));
        assertThat(m.primaryNode()).isEqualTo(new MistakeVO.NodeRef("SE-3.2", "用例图"));
        assertThat(m.scene()).isEqualTo("PRACTICE");
        assertThat(m.practiceSessionId()).isEqualTo(55L);
        assertThat(m.answeredAt()).isEqualTo("2026-10-20T09:00:00");
        assertThat(report.topMisconceptions())
                .containsExactly(new MisconceptionSummary("SE-2.1", "需求获取", 1));
    }

    @Test
    @DisplayName("多选（\"C,A\" 未排序）：只有选错的选项贡献误区，正确选项按 key 排序")
    void multiChoice_onlyWrongChosenOptionsContribute() {
        question(2L, QuestionType.MULTI, "多选题", "A,B", 100L);
        option(2L, "A", "甲", true, "SE-1.1");
        option(2L, "B", "乙", true, null);
        option(2L, "C", "丙", false, "SE-2.1");
        answer(12L, 2L, "C,a", 1, null, LocalDateTime.of(2026, 10, 20, 10, 0));

        MistakeVO m = service.report(STUDENT, COURSE, null, null, 3).mistakes().get(0);

        assertThat(m.chosen()).extracting(MistakeVO.OptionRef::key).containsExactly("C", "A");
        assertThat(m.correct()).extracting(MistakeVO.OptionRef::key).containsExactly("A", "B");
        assertThat(m.misconceptionNodes()).extracting(MistakeVO.NodeRef::nodeCode).containsExactly("SE-2.1");
        assertThat(m.scene()).isEqualTo("DIAGNOSTIC");
    }

    @Test
    @DisplayName("判断题计入；简答题排除")
    void trueFalseIncluded_shortAnswerExcluded() {
        question(3L, QuestionType.TRUE_FALSE, "判断题", "A", 100L);
        option(3L, "A", "正确", true, null);
        option(3L, "B", "错误", false, null);
        question(4L, QuestionType.SHORT_ANSWER, "简答题", "参考答案", 100L);
        answer(13L, 3L, "B", 2, 1L, LocalDateTime.of(2026, 10, 20, 11, 0));
        answer(14L, 4L, "我不知道", 2, 1L, LocalDateTime.of(2026, 10, 20, 12, 0));

        List<MistakeVO> list = service.recentMistakes(STUDENT, COURSE, null, null, 5);

        assertThat(list).extracting(MistakeVO::questionId).containsExactly(3L);
        assertThat(list.get(0).misconceptionNodes()).isEmpty();
        assertThat(list.get(0).chosen()).containsExactly(new MistakeVO.OptionRef("B", "错误"));
    }

    @Test
    @DisplayName("归档节点从误区中剔除；无选项文本时回退 question.answer 作为正确答案")
    void archivedMisconceptionDropped_answerFallback() {
        question(5L, QuestionType.SINGLE, "题", "A", 100L);
        option(5L, "B", "旧概念", false, "SE-OLD");
        answer(15L, 5L, "B", 2, 1L, LocalDateTime.of(2026, 10, 20, 9, 0));

        MistakeReport report = service.report(STUDENT, COURSE, null, null, 3);

        assertThat(report.mistakes().get(0).misconceptionNodes()).isEmpty();
        assertThat(report.mistakes().get(0).correct()).containsExactly(new MistakeVO.OptionRef("A", null));
        assertThat(report.topMisconceptions()).isEmpty();
    }

    @Test
    @DisplayName("practiceSessionId 过滤：只保留 scene=2 且 session_id 相同的记录")
    void sessionFilter() {
        question(1L, QuestionType.SINGLE, "题1", "A", 100L);
        question(2L, QuestionType.SINGLE, "题2", "A", 100L);
        question(3L, QuestionType.SINGLE, "题3", "A", 100L);
        answer(21L, 1L, "B", 2, 88L, LocalDateTime.of(2026, 10, 20, 9, 0));
        answer(22L, 2L, "B", 2, 99L, LocalDateTime.of(2026, 10, 20, 8, 0));
        answer(23L, 3L, "B", 1, 88L, LocalDateTime.of(2026, 10, 20, 7, 0)); // diagnostic with same id

        List<MistakeVO> list = service.recentMistakes(STUDENT, COURSE, null, 88L, 5);

        assertThat(list).extracting(MistakeVO::questionId).containsExactly(1L);
    }

    @Test
    @DisplayName("nodeCode 过滤：主节点或误区节点命中均保留")
    void nodeCodeFilter_primaryOrMisconception() {
        question(1L, QuestionType.SINGLE, "主节点是 SE-3.2", "A", 100L);
        question(2L, QuestionType.SINGLE, "主节点 SE-1.1，误区 SE-3.2", "A", 102L);
        question(3L, QuestionType.SINGLE, "无关", "A", 102L);
        option(2L, "B", "x", false, "SE-3.2");
        answer(31L, 1L, "B", 2, 1L, LocalDateTime.of(2026, 10, 20, 9, 0));
        answer(32L, 2L, "B", 2, 1L, LocalDateTime.of(2026, 10, 20, 8, 0));
        answer(33L, 3L, "B", 2, 1L, LocalDateTime.of(2026, 10, 20, 7, 0));

        List<MistakeVO> list = service.recentMistakes(STUDENT, COURSE, "SE-3.2", null, 5);

        assertThat(list).extracting(MistakeVO::questionId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("limit 生效；同一题多次错只保留最近一次，但误区命中按次数累计并降序取前 3")
    void limitDedupAndTopMisconceptions() {
        question(1L, QuestionType.SINGLE, "题1", "A", 100L);
        question(2L, QuestionType.SINGLE, "题2", "A", 100L);
        question(3L, QuestionType.SINGLE, "题3", "A", 100L);
        option(1L, "B", "b1", false, "SE-2.1");
        option(2L, "B", "b2", false, "SE-1.1");
        option(3L, "B", "b3", false, "SE-2.1");
        answer(41L, 1L, "B", 2, 1L, LocalDateTime.of(2026, 10, 20, 12, 0));
        answer(42L, 1L, "B", 2, 1L, LocalDateTime.of(2026, 10, 20, 11, 0));
        answer(43L, 2L, "B", 2, 1L, LocalDateTime.of(2026, 10, 20, 10, 0));
        answer(44L, 3L, "B", 2, 1L, LocalDateTime.of(2026, 10, 20, 9, 0));

        MistakeReport report = service.report(STUDENT, COURSE, null, null, 2);

        assertThat(report.mistakes()).extracting(MistakeVO::questionId).containsExactly(1L, 2L);
        assertThat(report.topMisconceptions()).containsExactly(
                new MisconceptionSummary("SE-2.1", "需求获取", 3),
                new MisconceptionSummary("SE-1.1", "软件工程概述", 1));
    }

    @Test
    @DisplayName("无错题 → 空报告，不再查询题目")
    void noRecords_emptyReport() {
        MistakeReport report = service.report(STUDENT, COURSE, null, null, 3);
        assertThat(report.mistakes()).isEmpty();
        assertThat(report.topMisconceptions()).isEmpty();
        verify(questionMapper, never()).selectBatchIds(anyCollection());
    }

    @Test
    @DisplayName("复盘会话归属：不存在 / 他人 / 其他课程 → NOT_FOUND；本人本课 → 通过")
    void assertPracticeSessionOwned() {
        PracticeSession own = new PracticeSession();
        own.setId(5L);
        own.setStudentId(STUDENT);
        own.setCourseId(COURSE);
        PracticeSession other = new PracticeSession();
        other.setId(6L);
        other.setStudentId(99L);
        other.setCourseId(COURSE);
        PracticeSession otherCourse = new PracticeSession();
        otherCourse.setId(8L);
        otherCourse.setStudentId(STUDENT);
        otherCourse.setCourseId(4L);
        when(practiceSessionMapper.selectById(5L)).thenReturn(own);
        when(practiceSessionMapper.selectById(6L)).thenReturn(other);
        when(practiceSessionMapper.selectById(8L)).thenReturn(otherCourse);

        service.assertPracticeSessionOwned(STUDENT, COURSE, 5L);
        for (long id : new long[]{6L, 7L, 8L}) {
            assertThatThrownBy(() -> service.assertPracticeSessionOwned(STUDENT, COURSE, id))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo(ResultCode.NOT_FOUND.getCode()));
        }
    }

    // ── fixtures ──

    private void node(Long id, String code, String name, boolean archived) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setCourseId(COURSE);
        n.setNodeCode(code);
        n.setName(name);
        if (archived) {
            n.setArchivedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        }
        nodes.add(n);
    }

    private void question(Long id, int type, String stem, String answer, Long primaryNodeId) {
        Question q = new Question();
        q.setId(id);
        q.setCourseId(COURSE);
        q.setType(type);
        q.setStem(stem);
        q.setAnswer(answer);
        questions.add(q);
        QuestionNode qn = new QuestionNode();
        qn.setQuestionId(id);
        qn.setNodeId(primaryNodeId);
        qn.setWeight(1);
        questionNodes.add(qn);
    }

    private void option(Long questionId, String key, String text, boolean correct, String pointNodeCode) {
        QuestionOption o = new QuestionOption();
        o.setQuestionId(questionId);
        o.setOptionKey(key);
        o.setOptionText(text);
        o.setIsCorrect(correct ? 1 : 0);
        o.setPointNodeCode(pointNodeCode);
        options.add(o);
    }

    private void answer(Long id, Long questionId, String studentAnswer, int scene, Long sessionId,
                        LocalDateTime at) {
        AnswerRecord r = new AnswerRecord();
        r.setId(id);
        r.setStudentId(STUDENT);
        r.setCourseId(COURSE);
        r.setQuestionId(questionId);
        r.setStudentAnswer(studentAnswer);
        r.setIsCorrect(0);
        r.setScene(scene);
        r.setSessionId(sessionId);
        r.setAnsweredAt(at);
        records.add(r);
    }
}
