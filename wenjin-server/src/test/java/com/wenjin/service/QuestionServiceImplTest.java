package com.wenjin.service;

import com.wenjin.ai.QuestionAiClient;
import com.wenjin.ai.dto.AiAnnotation;
import com.wenjin.ai.dto.AiDistractor;
import com.wenjin.ai.dto.AiQuestion;
import com.wenjin.dto.AnnotateItemResult;
import com.wenjin.dto.AnnotateRequest;
import com.wenjin.dto.GenerateResult;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.impl.QuestionServiceImpl;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QuestionServiceImpl.generate 单元测试：用 Mockito 隔离数据库/AI，覆盖
 *   A 全合法落库、B 非法丢弃并重试、C 题干去重、D 答案非法丢弃。
 */
@ExtendWith(MockitoExtension.class)
class QuestionServiceImplTest {

    @Mock GraphQueryService graphQueryService;
    @Mock QuestionAiClient questionAiClient;
    @Mock KgNodeMapper kgNodeMapper;
    @Mock QuestionMapper questionMapper;
    @Mock QuestionOptionMapper questionOptionMapper;
    @Mock QuestionNodeMapper questionNodeMapper;

    private static final Long COURSE_ID = 1L;

    private QuestionServiceImpl service() {
        return new QuestionServiceImpl(
                graphQueryService, questionAiClient, kgNodeMapper,
                questionMapper, questionOptionMapper, questionNodeMapper);
    }

    /** 默认白名单与节点环境：whitelist={KT07,KT05,KT04}，目标 KT07。 */
    private void stubGraph() {
        when(graphQueryService.whitelistOf(COURSE_ID, "KT07", 2))
                .thenReturn(Set.of("KT07", "KT05", "KT04"));
        when(graphQueryService.codeToId(COURSE_ID))
                .thenReturn(Map.of("KT07", 70L, "KT05", 50L, "KT04", 40L));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                kgNode(70L, "KT07", "继承"),
                kgNode(50L, "KT05", "类与对象"),
                kgNode(40L, "KT04", "封装")));
    }

    /** 让 questionMapper.insert 模拟自增主键。 */
    private void stubInsertAutoId() {
        AtomicLong seq = new AtomicLong(1000L);
        when(questionMapper.insert(any(Question.class))).thenAnswer(inv -> {
            inv.getArgument(0, Question.class).setId(seq.incrementAndGet());
            return 1;
        });
    }

    // ───────────────────────── 用例 A：全合法 ─────────────────────────

    @Test
    @DisplayName("A 全合法：2 道合法题全部落库，status=PENDING，主点 weight=1")
    void allValidPersisted() {
        stubGraph();
        stubInsertAutoId();
        when(questionMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(questionAiClient.generate(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(List.of(validQuestion("继承表示？"), validQuestion("封装表示？")));

        GenerateResult result = service().generate(COURSE_ID, "KT07", 2);

        assertThat(result.getGenerated()).isEqualTo(2);
        assertThat(result.getDropped()).isZero();
        assertThat(result.getDuplicated()).isZero();
        assertThat(result.getQuestionIds()).hasSize(2);

        // 只调一次 AI（第一轮已满足）
        verify(questionAiClient, times(1))
                .generate(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());

        // question 落库 2 次，status=PENDING，source=2，type=1
        ArgumentCaptor<Question> qCaptor = ArgumentCaptor.forClass(Question.class);
        verify(questionMapper, times(2)).insert(qCaptor.capture());
        assertThat(qCaptor.getAllValues()).allSatisfy(q -> {
            assertThat(q.getStatus()).isEqualTo(QuestionStatus.PENDING);
            assertThat(q.getSource()).isEqualTo(2);
            assertThat(q.getType()).isEqualTo(1);
            assertThat(q.getAnswer()).isEqualTo("A"); // validQuestion 正确项是 A
        });

        // question_node 含 weight=1 主点（每题：1 主 + 1 次）
        ArgumentCaptor<QuestionNode> nCaptor = ArgumentCaptor.forClass(QuestionNode.class);
        verify(questionNodeMapper, times(4)).insert(nCaptor.capture());
        assertThat(nCaptor.getAllValues())
                .anyMatch(n -> n.getWeight() == 1 && n.getNodeId().equals(70L));
        assertThat(nCaptor.getAllValues())
                .filteredOn(n -> n.getWeight() == 1).hasSize(2);

        // 选项落库：每题 3 个选项
        verify(questionOptionMapper, times(6)).insert(any(QuestionOption.class));
    }

    // ───────────────────── 用例 B：非法被丢 + 重试 ─────────────────────

    @Test
    @DisplayName("B 干扰项考点不在白名单：丢弃非法题，AI 重试一次，dropped>=1")
    void invalidDroppedAndRetried() {
        stubGraph();
        stubInsertAutoId();
        when(questionMapper.selectList(any())).thenReturn(new ArrayList<>());

        AiQuestion legal = validQuestion("第一轮合法题");
        AiQuestion illegal = validQuestion("第一轮非法题");
        // 把某干扰项考点改为白名单外 KTXX
        illegal.getOptions().get(1).setPointNodeCode("KTXX");

        AiQuestion legal2 = validQuestion("第二轮合法题");

        when(questionAiClient.generate(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(List.of(legal, illegal))   // 第一轮：1 合法 1 非法
                .thenReturn(List.of(legal2));          // 第二轮：补 1 合法

        GenerateResult result = service().generate(COURSE_ID, "KT07", 2);

        // AI 被调两次（重试一次）
        verify(questionAiClient, times(2))
                .generate(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        assertThat(result.getDropped()).isGreaterThanOrEqualTo(1);
        assertThat(result.getGenerated()).isEqualTo(2);
    }

    // ───────────────────────── 用例 C：去重 ─────────────────────────

    @Test
    @DisplayName("C 题干已存在：跳过该题，duplicated==1，不抛异常")
    void duplicateSkipped() {
        stubGraph();
        // 去重在落库前发生，无需 stub insert
        // 任意 selectList 都返回非空 → 该题视为已存在
        when(questionMapper.selectList(any())).thenReturn(List.of(new Question()));
        when(questionAiClient.generate(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(List.of(validQuestion("重复题干")));

        GenerateResult result = service().generate(COURSE_ID, "KT07", 1);

        assertThat(result.getDuplicated()).isEqualTo(1);
        assertThat(result.getGenerated()).isZero();
        // 去重不落库
        verify(questionMapper, times(0)).insert(any(Question.class));
    }

    // ──────────────── 用例 D：答案非法（无 correct / 答案不在选项内） ────────────────

    @Test
    @DisplayName("D 无正确项的题被丢弃，不落库")
    void noCorrectDropped() {
        stubGraph();
        // 落库相关 stub 用 lenient，避免 D 不触发落库时 strict stubbing 报错
        lenient().when(questionMapper.selectList(any())).thenReturn(new ArrayList<>());

        AiQuestion noCorrect = validQuestion("没有正确项");
        for (AiDistractor opt : noCorrect.getOptions()) {
            opt.setCorrect(false);
        }
        // 期望 1 道但第一轮 0 道有效 → 会重试，第二轮也返回同样非法 → 仍 0 道
        when(questionAiClient.generate(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(List.of(noCorrect))
                .thenReturn(List.of(noCorrect));

        GenerateResult result = service().generate(COURSE_ID, "KT07", 1);

        assertThat(result.getGenerated()).isZero();
        assertThat(result.getDropped()).isGreaterThanOrEqualTo(1);
        verify(questionMapper, times(0)).insert(any(Question.class));
    }

    // ──────────────── 用例 E：存量题标注 在纲 → 落库 ────────────────

    @Test
    @DisplayName("E 在纲存量题：mainPoint=KT10 → 落库，question_node 主点 weight=1，persisted=true")
    void annotateInScopePersisted() {
        when(graphQueryService.allNodeCodes(COURSE_ID))
                .thenReturn(Set.of("KT10", "KT05", "KT04"));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                kgNode(100L, "KT10", "多态"),
                kgNode(50L, "KT05", "类与对象"),
                kgNode(40L, "KT04", "封装")));
        when(graphQueryService.codeToId(COURSE_ID))
                .thenReturn(Map.of("KT10", 100L, "KT05", 50L, "KT04", 40L));
        stubInsertAutoId();

        AiAnnotation ann = new AiAnnotation();
        ann.setMainPoint("KT10");
        ann.setSubPoints(List.of("KT05"));
        ann.setDistractors(List.of(option("B", "干扰项1", false, "KT05")));
        ann.setReason("命中多态");
        when(questionAiClient.annotate(any(), any(), any())).thenReturn(ann);

        AnnotateRequest req = request(item("多态指什么？",
                reqOption("A", "正确项", true),
                reqOption("B", "干扰项1", false),
                reqOption("C", "干扰项2", false)));

        List<AnnotateItemResult> results = service().annotate(COURSE_ID, req);

        assertThat(results).hasSize(1);
        AnnotateItemResult r = results.get(0);
        assertThat(r.isPersisted()).isTrue();
        assertThat(r.getMainPoint()).isEqualTo("KT10");
        assertThat(r.getSubPoints()).containsExactly("KT05");
        assertThat(r.getReason()).isEqualTo("命中多态");

        // question 落库一次：status=PENDING、source=1、type=1、答案=A
        ArgumentCaptor<Question> qCaptor = ArgumentCaptor.forClass(Question.class);
        verify(questionMapper, times(1)).insert(qCaptor.capture());
        Question q = qCaptor.getValue();
        assertThat(q.getStatus()).isEqualTo(QuestionStatus.PENDING);
        assertThat(q.getSource()).isEqualTo(1);
        assertThat(q.getType()).isEqualTo(1);
        assertThat(q.getAnswer()).isEqualTo("A");

        // 选项落库 3 个
        verify(questionOptionMapper, times(3)).insert(any(QuestionOption.class));

        // question_node：主点 KT10(weight=1) + 次点 KT05(weight=2)
        ArgumentCaptor<QuestionNode> nCaptor = ArgumentCaptor.forClass(QuestionNode.class);
        verify(questionNodeMapper, times(2)).insert(nCaptor.capture());
        assertThat(nCaptor.getAllValues())
                .anyMatch(n -> n.getWeight() == 1 && n.getNodeId().equals(100L));
        assertThat(nCaptor.getAllValues())
                .anyMatch(n -> n.getWeight() == 2 && n.getNodeId().equals(50L));
    }

    // ──────────────── 用例 F：存量题标注 超纲 → 不强标 ────────────────

    @Test
    @DisplayName("F 超纲存量题：mainPoint=null → 不落库，persisted=false，reason 透传")
    void annotateOutOfScopeNotPersisted() {
        lenient().when(graphQueryService.allNodeCodes(COURSE_ID))
                .thenReturn(Set.of("KT10", "KT05", "KT04"));
        lenient().when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                kgNode(100L, "KT10", "多态")));
        lenient().when(graphQueryService.codeToId(COURSE_ID))
                .thenReturn(Map.of("KT10", 100L));

        AiAnnotation ann = new AiAnnotation();
        ann.setMainPoint(null);
        ann.setReason("超出本课程范围");
        when(questionAiClient.annotate(any(), any(), any())).thenReturn(ann);

        AnnotateRequest req = request(item("一道超纲题？",
                reqOption("A", "选项A", true),
                reqOption("B", "选项B", false)));

        List<AnnotateItemResult> results = service().annotate(COURSE_ID, req);

        assertThat(results).hasSize(1);
        AnnotateItemResult r = results.get(0);
        assertThat(r.isPersisted()).isFalse();
        assertThat(r.getMainPoint()).isNull();
        assertThat(r.getReason()).isEqualTo("超出本课程范围");

        // 超纲不落库：question / option / question_node 都不写
        verify(questionMapper, times(0)).insert(any(Question.class));
        verify(questionOptionMapper, times(0)).insert(any(QuestionOption.class));
        verify(questionNodeMapper, times(0)).insert(any(QuestionNode.class));
    }

    // ───────────────────────── 测试辅助 ─────────────────────────

    /** 一道合法单选题：3 选项（A 正确；B、C 为白名单内干扰项），主点 KT07，次点 KT05。 */
    private AiQuestion validQuestion(String stem) {
        AiQuestion q = new AiQuestion();
        q.setStem(stem);
        q.setAnalysis("解析");
        q.setDifficulty(3);
        q.setMainPoint("KT07");
        q.setSubPoints(List.of("KT05"));
        q.setOptions(new ArrayList<>(List.of(
                option("A", "正确项", true, null),
                option("B", "干扰项1", false, "KT05"),
                option("C", "干扰项2", false, "KT04"))));
        return q;
    }

    private AiDistractor option(String key, String text, boolean correct, String pointCode) {
        AiDistractor d = new AiDistractor();
        d.setOptionKey(key);
        d.setText(text);
        d.setCorrect(correct);
        d.setPointNodeCode(pointCode);
        return d;
    }

    private AnnotateRequest request(AnnotateRequest.Item... items) {
        AnnotateRequest req = new AnnotateRequest();
        req.setItems(new ArrayList<>(List.of(items)));
        return req;
    }

    private AnnotateRequest.Item item(String stem, AnnotateRequest.Option... options) {
        AnnotateRequest.Item it = new AnnotateRequest.Item();
        it.setStem(stem);
        it.setOptions(new ArrayList<>(List.of(options)));
        return it;
    }

    private AnnotateRequest.Option reqOption(String key, String text, boolean correct) {
        AnnotateRequest.Option o = new AnnotateRequest.Option();
        o.setKey(key);
        o.setText(text);
        o.setCorrect(correct);
        return o;
    }

    private KgNode kgNode(Long id, String code, String name) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setNodeCode(code);
        n.setName(name);
        n.setChapter("第三章");
        n.setCourseId(COURSE_ID);
        return n;
    }
}
