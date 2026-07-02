package com.wenjin.service;

import com.wenjin.ai.QuestionAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.DiagnosticResultVO;
import com.wenjin.dto.LearningPathVO;
import com.wenjin.dto.PathGenerateRequest;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.LearningPath;
import com.wenjin.entity.LearningPathItem;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.LearningPathItemMapper;
import com.wenjin.mapper.LearningPathMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.impl.PathServiceImpl;
import com.wenjin.support.QuestionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PathServiceImpl 单测：聚焦集合/拓扑序/同层最弱先/防环/持久化/reason 模板/useAi 回退/完成置位。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PathServiceImplTest {

    @Mock StudentMasteryMapper studentMasteryMapper;
    @Mock KgNodeMapper kgNodeMapper;
    @Mock KgEdgeMapper kgEdgeMapper;
    @Mock LearningPathMapper learningPathMapper;
    @Mock LearningPathItemMapper learningPathItemMapper;
    @Mock DiagnosticResultService diagnosticResultService;
    @Mock QuestionAiClient questionAiClient;
    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock QuestionMapper questionMapper;

    private static final Long S = 2L, C = 1L;

    /** completeItem 现做归属校验（item→path→studentId vs 当前用户），每例后清 ThreadLocal。 */
    @AfterEach
    void clearCurrentUser() {
        CurrentUser.clear();
    }

    /**
     * 默认 stub：questionNodeMapper/questionMapper 返回空列表，令现有测试不因新增 buildAvailableCountMap 调用而 NPE。
     * LENIENT 模式下未用到也不报错。
     */
    @BeforeEach
    void stubAvailCountDefaults() {
        when(questionNodeMapper.selectList(any())).thenReturn(List.of());
        when(questionMapper.selectList(any())).thenReturn(List.of());
    }

    private LearningPath path(long id, long studentId) {
        LearningPath p = new LearningPath();
        p.setId(id); p.setStudentId(studentId); p.setCourseId(C); p.setStatus(1);
        return p;
    }

    private PathServiceImpl impl() {
        PathServiceImpl impl = new PathServiceImpl(studentMasteryMapper, kgNodeMapper, kgEdgeMapper,
                learningPathMapper, learningPathItemMapper, diagnosticResultService, questionAiClient,
                questionNodeMapper, questionMapper);
        ReflectionTestUtils.setField(impl, "masteredThreshold", 75.0);
        return impl;
    }

    private KgNode node(long id, String code, String name) {
        KgNode n = new KgNode();
        n.setId(id); n.setCourseId(C); n.setNodeCode(code); n.setName(name); n.setChapter("ch");
        return n;
    }

    private KgEdge prereq(long from, long to) {
        KgEdge e = new KgEdge();
        e.setCourseId(C); e.setFromNodeId(from); e.setToNodeId(to); e.setRelationType(1);
        return e;
    }

    private StudentMastery mastery(long nodeId, String score, int level) {
        StudentMastery m = new StudentMastery();
        m.setStudentId(S); m.setCourseId(C); m.setNodeId(nodeId);
        m.setMasteryScore(new BigDecimal(score)); m.setMasteryLevel(level);
        return m;
    }

    /** 让 insert(LearningPath) 回填自增 id=777。 */
    private void stubInsertPathId() {
        doAnswer(inv -> { ((LearningPath) inv.getArgument(0)).setId(777L); return 1; })
                .when(learningPathMapper).insert(any(LearningPath.class));
    }

    private LearningPathItem item(long id, long pathId, long nodeId, int order, int status) {
        LearningPathItem it = new LearningPathItem();
        it.setId(id); it.setPathId(pathId); it.setNodeId(nodeId);
        it.setStepOrder(order); it.setStatus(status); it.setReason("r");
        return it;
    }

    @Test
    @DisplayName("A 聚焦集合+拓扑序：A→B→target，已掌握前置剪枝；前置排在依赖它的点前")
    void focusedTopoOrder() {
        // X(已掌握)→A(未学)→B(薄弱)→T(卡点薄弱)；X 应被剪枝
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                node(1, "X", "已掌握前置"), node(2, "A", "甲"),
                node(3, "B", "乙"), node(4, "T", "卡点")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(
                prereq(1, 2), prereq(2, 3), prereq(3, 4)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(
                mastery(1, "90.00", 2), mastery(3, "50.00", 1), mastery(4, "30.00", 0)));
        stubInsertPathId();

        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(S); req.setCourseId(C); req.setTargetNodeId(4L);

        LearningPathVO vo = impl().generate(req);

        // 集合 = {A,B,T}（X 已掌握剪枝），顺序 A→B→T
        assertThat(vo.getSteps()).extracting(LearningPathVO.StepVO::getNodeCode)
                .containsExactly("A", "B", "T");
        assertThat(vo.getSteps()).extracting(LearningPathVO.StepVO::getStepOrder)
                .containsExactly(1, 2, 3);
        assertThat(vo.getTargetNode().getNodeCode()).isEqualTo("T");
        assertThat(vo.getProgress().getTotal()).isEqualTo(3);
        assertThat(vo.getProgress().getDone()).isEqualTo(0);
    }

    @Test
    @DisplayName("B 同层按薄弱程度优先：同为 target 前置，低分者先")
    void sameLayerWeakestFirst() {
        // A(未学,0) 与 B(薄弱,60) 都是 T 的前置，互不依赖 → 同层，A(更弱)先
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                node(1, "A", "甲"), node(2, "B", "乙"), node(3, "T", "卡点")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 3), prereq(2, 3)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(
                mastery(2, "60.00", 1), mastery(3, "30.00", 0)));  // A 无行=未学=0
        stubInsertPathId();

        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(S); req.setCourseId(C); req.setTargetNodeId(3L);

        LearningPathVO vo = impl().generate(req);

        assertThat(vo.getSteps()).extracting(LearningPathVO.StepVO::getNodeCode)
                .containsExactly("A", "B", "T");
    }

    @Test
    @DisplayName("C 持久化：旧路径置失效 + 插入 path + 逐步 item(status=0)")
    void persists() {
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(4, "T", "卡点")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(mastery(4, "30.00", 0)));
        stubInsertPathId();

        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(S); req.setCourseId(C); req.setTargetNodeId(4L);

        impl().generate(req);

        // 旧路径失效：update(null, wrapper) 调一次
        verify(learningPathMapper, times(1)).update(any(), any());
        verify(learningPathMapper, times(1)).insert(any(LearningPath.class));
        ArgumentCaptor<LearningPathItem> itemCap = ArgumentCaptor.forClass(LearningPathItem.class);
        verify(learningPathItemMapper, times(1)).insert(itemCap.capture());
        LearningPathItem it = itemCap.getValue();
        assertThat(it.getPathId()).isEqualTo(777L);
        assertThat(it.getNodeId()).isEqualTo(4L);
        assertThat(it.getStepOrder()).isEqualTo(1);
        assertThat(it.getStatus()).isEqualTo(0);
        assertThat(it.getReason()).isNotBlank();
    }

    @Test
    @DisplayName("D 防环：节点集存在环 → 抛 BusinessException")
    void cycleThrows() {
        // A↔B 互为前置（构造环），都未掌握，target=A
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1, "A", "甲"), node(2, "B", "乙")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2), prereq(2, 1)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(
                mastery(1, "30.00", 0), mastery(2, "30.00", 0)));
        stubInsertPathId();

        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(S); req.setCourseId(C); req.setTargetNodeId(1L);

        assertThatThrownBy(() -> impl().generate(req)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("E useAi=true：调 AI 生成 reason；AI 抛异常时回退模板")
    void useAiWithFallback() {
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(4, "T", "卡点")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(mastery(4, "30.00", 0)));
        stubInsertPathId();
        when(questionAiClient.explainLearningStep(anyString(), anyString(), anyString()))
                .thenThrow(new BusinessException(ResultCode.AI_ERROR, "boom"));

        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(S); req.setCourseId(C); req.setTargetNodeId(4L); req.setUseAi(true);

        LearningPathVO vo = impl().generate(req);

        verify(questionAiClient, times(1)).explainLearningStep(anyString(), anyString(), anyString());
        assertThat(vo.getSteps().get(0).getReason()).isNotBlank(); // 回退模板，非空
    }

    @Test
    @DisplayName("F useAi=false：不调 AI，用模板")
    void templateNoAi() {
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(4, "T", "卡点")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(mastery(4, "30.00", 0)));
        stubInsertPathId();

        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(S); req.setCourseId(C); req.setTargetNodeId(4L);

        impl().generate(req);

        verify(questionAiClient, never()).explainLearningStep(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("G completeItem：属主未完成→置 status=1 + completed_at")
    void completeSetsStatus() {
        CurrentUser.set(S); // 属主本人
        LearningPathItem item = new LearningPathItem();
        item.setId(55L); item.setPathId(777L); item.setStatus(0); item.setCompletedAt(null);
        when(learningPathItemMapper.selectById(55L)).thenReturn(item);
        when(learningPathMapper.selectById(777L)).thenReturn(path(777L, S));

        impl().completeItem(55L);

        ArgumentCaptor<LearningPathItem> cap = ArgumentCaptor.forClass(LearningPathItem.class);
        verify(learningPathItemMapper, times(1)).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(1);
        assertThat(cap.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("G2 completeItem 归属：非属主→FORBIDDEN，不写库")
    void completeItem_nonOwner_throwsForbidden_noWrite() {
        CurrentUser.set(9L); // 当前用户 9，步骤属于 studentId=2
        LearningPathItem item = new LearningPathItem();
        item.setId(55L); item.setPathId(777L); item.setStatus(0);
        when(learningPathItemMapper.selectById(55L)).thenReturn(item);
        when(learningPathMapper.selectById(777L)).thenReturn(path(777L, S));

        assertThatThrownBy(() -> impl().completeItem(55L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.getCode()));
        verify(learningPathItemMapper, never()).updateById(any(LearningPathItem.class));
    }

    @Test
    @DisplayName("G3 completeItem 匿名：未登录→UNAUTHORIZED，不写库")
    void completeItem_anonymous_throwsUnauthorized_noWrite() {
        // 不设 CurrentUser
        LearningPathItem item = new LearningPathItem();
        item.setId(55L); item.setPathId(777L); item.setStatus(0);
        when(learningPathItemMapper.selectById(55L)).thenReturn(item);
        when(learningPathMapper.selectById(777L)).thenReturn(path(777L, S));

        assertThatThrownBy(() -> impl().completeItem(55L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.UNAUTHORIZED.getCode()));
        verify(learningPathItemMapper, never()).updateById(any(LearningPathItem.class));
    }

    @Test
    @DisplayName("H 多根因：同层多个无前置节点都标 root（非仅首个）")
    void multipleRoots() {
        // A、B 互不依赖、同为 T 的前置 → 两者在子图入度都为 0，均应为 root
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                node(1, "A", "甲"), node(2, "B", "乙"), node(3, "T", "卡点")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 3), prereq(2, 3)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(
                mastery(1, "20.00", 0), mastery(2, "60.00", 1), mastery(3, "30.00", 0)));
        stubInsertPathId();

        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(S); req.setCourseId(C); req.setTargetNodeId(3L);

        LearningPathVO vo = impl().generate(req);

        var roleByCode = new java.util.HashMap<String, String>();
        for (LearningPathVO.StepVO s : vo.getSteps()) {
            roleByCode.put(s.getNodeCode(), s.getRole());
        }
        assertThat(roleByCode)
                .containsEntry("A", "root")
                .containsEntry("B", "root")
                .containsEntry("T", "stuck");
    }

    @Test
    @DisplayName("I completeItem 幂等：属主对已完成步骤再次调用→不写库、不覆盖时间")
    void completeAlreadyDoneIsIdempotent() {
        CurrentUser.set(S); // 属主本人
        LearningPathItem done = new LearningPathItem();
        done.setId(55L); done.setPathId(777L); done.setStatus(1);
        done.setCompletedAt(LocalDateTime.now().minusDays(1));
        when(learningPathItemMapper.selectById(55L)).thenReturn(done);
        when(learningPathMapper.selectById(777L)).thenReturn(path(777L, S));

        impl().completeItem(55L);

        verify(learningPathItemMapper, never()).updateById(any(LearningPathItem.class));
    }

    @Test
    @DisplayName("J getCurrent：返回有效路径步骤、进度统计、角色与目标")
    void getCurrentReturnsSteps() {
        LearningPath p = new LearningPath();
        p.setId(777L); p.setStudentId(S); p.setCourseId(C); p.setTargetNodeId(3L); p.setStatus(1);
        when(learningPathMapper.selectList(any())).thenReturn(List.of(p));
        // A(已完成)→B→T 链
        when(learningPathItemMapper.selectList(any())).thenReturn(List.of(
                item(11L, 777L, 1L, 1, 1), item(12L, 777L, 2L, 2, 0), item(13L, 777L, 3L, 3, 0)));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                node(1, "A", "甲"), node(2, "B", "乙"), node(3, "T", "卡点")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2), prereq(2, 3)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(
                mastery(1, "80.00", 2), mastery(2, "50.00", 1), mastery(3, "30.00", 0)));

        LearningPathVO vo = impl().getCurrent(S, C);

        assertThat(vo.getSteps()).hasSize(3);
        assertThat(vo.getProgress().getDone()).isEqualTo(1);
        assertThat(vo.getProgress().getTotal()).isEqualTo(3);
        assertThat(vo.getTargetNode().getNodeCode()).isEqualTo("T");
        var roleByCode = new java.util.HashMap<String, String>();
        for (LearningPathVO.StepVO s : vo.getSteps()) {
            roleByCode.put(s.getNodeCode(), s.getRole());
        }
        assertThat(roleByCode)
                .containsEntry("A", "root")
                .containsEntry("B", "prereq")
                .containsEntry("T", "stuck");
    }

    @Test
    @DisplayName("K targetNodeId 缺省：从诊断卡点解析 target（默认生产路径）")
    void resolvesTargetFromDiagnostic() {
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(4, "T", "卡点")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(mastery(4, "30.00", 0)));
        stubInsertPathId();
        DiagnosticResultVO diag = new DiagnosticResultVO();
        diag.setHasWeakness(true);
        DiagnosticResultVO.NodeRef stuck = new DiagnosticResultVO.NodeRef();
        stuck.setNodeCode("T");
        diag.setStuckNode(stuck);
        when(diagnosticResultService.getResult(S, C)).thenReturn(diag);

        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(S); req.setCourseId(C); // targetNodeId 缺省

        LearningPathVO vo = impl().generate(req);

        verify(diagnosticResultService, times(1)).getResult(S, C);
        assertThat(vo.getTargetNode().getNodeCode()).isEqualTo("T");
        assertThat(vo.getSteps()).extracting(LearningPathVO.StepVO::getNodeCode).containsExactly("T");
    }

    // ═══════════════════════════════════════════════════════════════
    // M / N / O / P  — availableQuestionCount（T8）
    // ═══════════════════════════════════════════════════════════════

    /** 构造一个 QuestionNode 链接（nodeId → questionId）。 */
    private QuestionNode link(long questionId, long nodeId) {
        QuestionNode qn = new QuestionNode();
        qn.setId(questionId * 100 + nodeId); // 随意唯一 id
        qn.setQuestionId(questionId);
        qn.setNodeId(nodeId);
        qn.setWeight(1);
        return qn;
    }

    /** 构造一个 APPROVED 题目。 */
    private Question question(long id) {
        Question q = new Question();
        q.setId(id);
        q.setStatus(QuestionStatus.APPROVED);
        q.setStem("题干" + id);
        return q;
    }

    /** 构造一个 PENDING（未审核）题目。 */
    private Question pendingQuestion(long id) {
        Question q = new Question();
        q.setId(id);
        q.setStatus(QuestionStatus.PENDING);
        q.setStem("待审" + id);
        return q;
    }

    @Test
    @DisplayName("M generate(): availableQuestionCount 批量计算——questionNodeMapper/questionMapper 各调一次（防 N+1）")
    void generate_availableCountBatchedNoN1() {
        // 路径：A(未学)→T(卡点) — 两个节点
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                node(1, "A", "甲"), node(2, "T", "卡点")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(
                mastery(1, "20.00", 0), mastery(2, "30.00", 0)));
        stubInsertPathId();

        // 节点 1(A) 有 2 道 APPROVED 题，节点 2(T) 有 1 道 APPROVED 题
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(
                link(101L, 1L), link(102L, 1L), link(201L, 2L)));
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(101L), question(102L), question(201L)));

        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(S); req.setCourseId(C); req.setTargetNodeId(2L);

        LearningPathVO vo = impl().generate(req);

        // 断言 mapper 调用次数各为 1（不是 N 次）
        verify(questionNodeMapper, times(1)).selectList(any());
        verify(questionMapper, times(1)).selectList(any());

        // 断言 VO 中每个步骤的 availableQuestionCount 正确
        var countByCode = new java.util.HashMap<String, Integer>();
        for (LearningPathVO.StepVO s : vo.getSteps()) {
            countByCode.put(s.getNodeCode(), s.getAvailableQuestionCount());
        }
        assertThat(countByCode).containsEntry("A", 2).containsEntry("T", 1);
    }

    @Test
    @DisplayName("N generate(): 只有 status=1 的题计入可用数，PENDING/REJECTED 不计")
    void generate_availableCountOnlyApproved() {
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1, "A", "甲")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(mastery(1, "20.00", 0)));
        stubInsertPathId();

        // 节点 1 关联 3 道题：1 APPROVED + 1 PENDING + 1 REJECTED
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(
                link(10L, 1L), link(20L, 1L), link(30L, 1L)));
        // questionMapper 只返回 APPROVED 的那道（模拟 status=1 过滤）
        when(questionMapper.selectList(any())).thenReturn(List.of(question(10L)));

        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(S); req.setCourseId(C); req.setTargetNodeId(1L);

        LearningPathVO vo = impl().generate(req);

        assertThat(vo.getSteps()).hasSize(1);
        assertThat(vo.getSteps().get(0).getAvailableQuestionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("O generate(): 无关联题的节点 availableQuestionCount=0")
    void generate_noQuestionsNodeCountIsZero() {
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1, "A", "甲")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(mastery(1, "20.00", 0)));
        stubInsertPathId();

        // questionNodeMapper 返回空（无关联题）
        when(questionNodeMapper.selectList(any())).thenReturn(List.of());

        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(S); req.setCourseId(C); req.setTargetNodeId(1L);

        LearningPathVO vo = impl().generate(req);

        assertThat(vo.getSteps()).hasSize(1);
        assertThat(vo.getSteps().get(0).getAvailableQuestionCount()).isEqualTo(0);
        // 无节点链接时 questionMapper 不应被调用
        verify(questionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("P getCurrent(): availableQuestionCount 同样批量计算——mapper 各调一次")
    void getCurrent_availableCountBatched() {
        LearningPath p = new LearningPath();
        p.setId(777L); p.setStudentId(S); p.setCourseId(C); p.setTargetNodeId(3L); p.setStatus(1);
        when(learningPathMapper.selectList(any())).thenReturn(List.of(p));
        when(learningPathItemMapper.selectList(any())).thenReturn(List.of(
                item(11L, 777L, 1L, 1, 0), item(12L, 777L, 3L, 2, 0)));
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                node(1, "A", "甲"), node(3, "T", "卡点")));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 3)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(
                mastery(1, "20.00", 0), mastery(3, "30.00", 0)));

        // 节点 1 有 1 道 APPROVED 题，节点 3 有 0 道
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(link(50L, 1L)));
        when(questionMapper.selectList(any())).thenReturn(List.of(question(50L)));

        LearningPathVO vo = impl().getCurrent(S, C);

        // 各调一次（批量 IN，不是每节点单独查）
        verify(questionNodeMapper, times(1)).selectList(any());
        verify(questionMapper, times(1)).selectList(any());

        var countByCode = new java.util.HashMap<String, Integer>();
        for (LearningPathVO.StepVO s : vo.getSteps()) {
            countByCode.put(s.getNodeCode(), s.getAvailableQuestionCount());
        }
        assertThat(countByCode).containsEntry("A", 1).containsEntry("T", 0);
    }
}
