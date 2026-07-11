package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenjin.dto.QuestionReviewRequest;
import com.wenjin.dto.QuestionStatusCount;
import com.wenjin.dto.TeacherQuestionPageVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.impl.TeacherQuestionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeacherQuestionServiceImplTest {

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private QuestionOptionMapper questionOptionMapper;

    @Mock
    private QuestionNodeMapper questionNodeMapper;

    @Mock
    private KgNodeMapper nodeMapper;

    @Mock
    private CourseAccessService courseAccessService;

    @InjectMocks
    private TeacherQuestionServiceImpl service;

    private Question q1, q2, q3;
    private QuestionOption opt1a, opt1b, opt2a, opt2b;
    private QuestionNode qn1, qn2;

    @BeforeEach
    void setUp() {
        lenient().doNothing().when(courseAccessService).assertManageCourse(anyLong());
        lenient().doNothing().when(courseAccessService)
                .assertQuestionsBelongToCourse(anyLong(), any());

        q1 = new Question();
        q1.setId(101L);
        q1.setCourseId(1L);
        q1.setStem("Question 1");
        q1.setType(1);
        q1.setDifficulty(3);
        q1.setConfidence(90);
        q1.setStatus(0);
        q1.setSource(1);
        q1.setCreatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));

        q2 = new Question();
        q2.setId(102L);
        q2.setCourseId(1L);
        q2.setStem("Question 2");
        q2.setType(2);
        q2.setDifficulty(5);
        q2.setConfidence(75);
        q2.setStatus(1);
        q2.setSource(2);
        q2.setCreatedAt(LocalDateTime.of(2026, 6, 2, 10, 0));

        q3 = new Question();
        q3.setId(103L);
        q3.setCourseId(1L);
        q3.setStem("Question 3");
        q3.setType(1);
        q3.setDifficulty(2);
        q3.setConfidence(60);
        q3.setStatus(2);
        q3.setSource(1);
        q3.setCreatedAt(LocalDateTime.of(2026, 6, 3, 10, 0));

        opt1a = new QuestionOption();
        opt1a.setId(1001L);
        opt1a.setQuestionId(101L);
        opt1a.setOptionKey("A");
        opt1a.setOptionText("Option A");
        opt1a.setIsCorrect(1);
        opt1a.setPointNodeCode("N001");

        opt1b = new QuestionOption();
        opt1b.setId(1002L);
        opt1b.setQuestionId(101L);
        opt1b.setOptionKey("B");
        opt1b.setOptionText("Option B");
        opt1b.setIsCorrect(0);
        opt1b.setPointNodeCode(null);

        opt2a = new QuestionOption();
        opt2a.setId(1003L);
        opt2a.setQuestionId(102L);
        opt2a.setOptionKey("A");
        opt2a.setOptionText("Option A2");
        opt2a.setIsCorrect(1);
        opt2a.setPointNodeCode("N002");

        opt2b = new QuestionOption();
        opt2b.setId(1004L);
        opt2b.setQuestionId(102L);
        opt2b.setOptionKey("B");
        opt2b.setOptionText("Option B2");
        opt2b.setIsCorrect(1);
        opt2b.setPointNodeCode("N002");

        qn1 = new QuestionNode();
        qn1.setId(501L);
        qn1.setQuestionId(101L);
        qn1.setNodeId(201L);
        qn1.setWeight(1);

        qn2 = new QuestionNode();
        qn2.setId(502L);
        qn2.setQuestionId(102L);
        qn2.setNodeId(202L);
        qn2.setWeight(1);
    }

    private void stubStatusCounts() {
        QuestionStatusCount c0 = new QuestionStatusCount();
        c0.setStatus(0);
        c0.setCount(1L);
        QuestionStatusCount c1 = new QuestionStatusCount();
        c1.setStatus(1);
        c1.setCount(1L);
        QuestionStatusCount c2 = new QuestionStatusCount();
        c2.setStatus(2);
        c2.setCount(1L);
        when(questionMapper.countGroupedByStatus(1L)).thenReturn(Arrays.asList(c0, c1, c2));
    }

    private void stubBatchLoadFor(List<Question> page) {
        if (page.isEmpty()) {
            return;
        }
        when(questionOptionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(opt1a, opt1b, opt2a, opt2b));
        when(questionNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(qn1, qn2));
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    KgNode n1 = new KgNode();
                    n1.setId(201L);
                    n1.setNodeCode("N001");
                    n1.setName("Node 1");
                    KgNode n2 = new KgNode();
                    n2.setId(202L);
                    n2.setNodeCode("N002");
                    n2.setName("Node 2");
                    return Arrays.asList(n1, n2);
                });
    }

    @Test
    @DisplayName("ownership assert 先于分页查询")
    void assertManageCourseRunsFirstOnList() {
        when(questionMapper.selectTeacherPage(eq(1L), isNull(), isNull(), isNull(), eq(0), eq(10)))
                .thenReturn(Collections.emptyList());
        when(questionMapper.countTeacherFiltered(eq(1L), isNull(), isNull(), isNull())).thenReturn(0L);
        when(questionMapper.countGroupedByStatus(1L)).thenReturn(Collections.emptyList());

        service.list(1L, null, null, null, 1, 10);

        InOrder order = inOrder(courseAccessService, questionMapper);
        order.verify(courseAccessService).assertManageCourse(1L);
        order.verify(questionMapper).selectTeacherPage(anyLong(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void listPassesLimitOffsetToMapperAndLoadsOptionsAndMainNode() {
        when(questionMapper.selectTeacherPage(eq(1L), isNull(), isNull(), isNull(), eq(0), eq(10)))
                .thenReturn(Arrays.asList(q1, q2, q3));
        when(questionMapper.countTeacherFiltered(eq(1L), isNull(), isNull(), isNull())).thenReturn(3L);
        stubStatusCounts();
        stubBatchLoadFor(Arrays.asList(q1, q2, q3));

        TeacherQuestionPageVO result = service.list(1L, null, null, null, 1, 10);

        assertThat(result.getTotal()).isEqualTo(3L);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(10);
        assertThat(result.getItems()).hasSize(3);
        // DB 已排序；服务层不再内存 sort
        assertThat(result.getItems().get(0).getConfidence()).isEqualTo(90);
        assertThat(result.getItems().get(0).getOptions()).hasSize(2);
        assertThat(result.getItems().get(0).getOptions().get(0).getKey()).isEqualTo("A");
        assertThat(result.getItems().get(0).getMainNodeCode()).isEqualTo("N001");
        assertThat(result.getCounts().getPending()).isEqualTo(1L);
        assertThat(result.getCounts().getPassed()).isEqualTo(1L);
        assertThat(result.getCounts().getRejected()).isEqualTo(1L);

        verify(questionMapper).selectTeacherPage(1L, null, null, null, 0, 10);
        verify(questionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("page/size 原样下推 OFFSET/LIMIT，服务层不静默修正")
    void listPushesPageSizeAsOffsetLimitWithoutClamping() {
        when(questionMapper.selectTeacherPage(eq(1L), isNull(), isNull(), isNull(), eq(40), eq(20)))
                .thenReturn(Collections.emptyList());
        when(questionMapper.countTeacherFiltered(eq(1L), isNull(), isNull(), isNull())).thenReturn(0L);
        when(questionMapper.countGroupedByStatus(1L)).thenReturn(Collections.emptyList());

        TeacherQuestionPageVO result = service.list(1L, null, null, null, 3, 20);

        assertThat(result.getPage()).isEqualTo(3);
        assertThat(result.getSize()).isEqualTo(20);
        verify(questionMapper).selectTeacherPage(1L, null, null, null, 40, 20);
    }

    @Test
    void reviewPassUpdatesStatusToApproved() {
        QuestionReviewRequest req = new QuestionReviewRequest();
        req.setIds(Arrays.asList(101L, 103L));
        req.setAction("pass");

        when(questionMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(2);

        int affected = service.review(1L, req);

        assertThat(affected).isEqualTo(2);
        verify(questionMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void reviewRejectUpdatesStatusToRejected() {
        QuestionReviewRequest req = new QuestionReviewRequest();
        req.setIds(Arrays.asList(101L, 102L));
        req.setAction("reject");

        when(questionMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(2);

        int affected = service.review(1L, req);

        assertThat(affected).isEqualTo(2);
        verify(questionMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void listValidatesInvalidCourseId() {
        assertThatThrownBy(() -> service.list(null, null, null, null, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("courseId");
    }

    @Test
    void reviewValidatesInvalidCourseId() {
        QuestionReviewRequest req = new QuestionReviewRequest();
        req.setIds(Arrays.asList(101L));
        req.setAction("pass");

        assertThatThrownBy(() -> service.review(null, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("courseId");
    }

    @Test
    void reviewValidatesNullRequest() {
        assertThatThrownBy(() -> service.review(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request");
    }

    @Test
    void reviewValidatesEmptyIds() {
        QuestionReviewRequest req = new QuestionReviewRequest();
        req.setIds(Collections.emptyList());
        req.setAction("pass");

        assertThatThrownBy(() -> service.review(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ids");
    }

    @Test
    void reviewValidatesInvalidAction() {
        QuestionReviewRequest req = new QuestionReviewRequest();
        req.setIds(Arrays.asList(101L));
        req.setAction("invalid");

        assertThatThrownBy(() -> service.review(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    @Test
    void listFiltersNodeCodeViaMapperJoin() {
        when(questionMapper.selectTeacherPage(eq(1L), isNull(), eq("N001"), isNull(), eq(0), eq(10)))
                .thenReturn(Arrays.asList(q1));
        when(questionMapper.countTeacherFiltered(eq(1L), isNull(), eq("N001"), isNull())).thenReturn(1L);
        stubStatusCounts();
        when(questionOptionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(opt1a, opt1b));
        when(questionNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(qn1));
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    KgNode n1 = new KgNode();
                    n1.setId(201L);
                    n1.setNodeCode("N001");
                    n1.setName("Node 1");
                    return Arrays.asList(n1);
                });

        TeacherQuestionPageVO result = service.list(1L, null, "N001", null, 1, 10);

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getId()).isEqualTo(101L);
        verify(questionMapper).selectTeacherPage(1L, null, "N001", null, 0, 10);
    }

    @Test
    void listFiltersConfidenceGe85() {
        when(questionMapper.selectTeacherPage(eq(1L), isNull(), isNull(), eq("ge85"), eq(0), eq(10)))
                .thenReturn(Arrays.asList(q1));
        when(questionMapper.countTeacherFiltered(eq(1L), isNull(), isNull(), eq("ge85"))).thenReturn(1L);
        stubStatusCounts();
        when(questionOptionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(opt1a, opt1b));
        when(questionNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(qn1));
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    KgNode n1 = new KgNode();
                    n1.setId(201L);
                    n1.setNodeCode("N001");
                    n1.setName("Node 1");
                    return Arrays.asList(n1);
                });

        TeacherQuestionPageVO result = service.list(1L, null, null, "ge85", 1, 10);

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getItems().get(0).getConfidence()).isEqualTo(90);
        verify(questionMapper).selectTeacherPage(1L, null, null, "ge85", 0, 10);
    }

    @Test
    void listFiltersConfidenceMid() {
        when(questionMapper.selectTeacherPage(eq(1L), isNull(), isNull(), eq("mid"), eq(0), eq(10)))
                .thenReturn(Arrays.asList(q2));
        when(questionMapper.countTeacherFiltered(eq(1L), isNull(), isNull(), eq("mid"))).thenReturn(1L);
        stubStatusCounts();
        when(questionOptionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(opt2a, opt2b));
        when(questionNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(qn2));
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    KgNode n2 = new KgNode();
                    n2.setId(202L);
                    n2.setNodeCode("N002");
                    n2.setName("Node 2");
                    return Arrays.asList(n2);
                });

        TeacherQuestionPageVO result = service.list(1L, null, null, "mid", 1, 10);

        assertThat(result.getItems().get(0).getConfidence()).isEqualTo(75);
        verify(questionMapper).selectTeacherPage(1L, null, null, "mid", 0, 10);
    }

    @Test
    void listFiltersConfidenceLt70() {
        when(questionMapper.selectTeacherPage(eq(1L), isNull(), isNull(), eq("lt70"), eq(0), eq(10)))
                .thenReturn(Arrays.asList(q3));
        when(questionMapper.countTeacherFiltered(eq(1L), isNull(), isNull(), eq("lt70"))).thenReturn(1L);
        stubStatusCounts();
        when(questionOptionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        when(questionNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        TeacherQuestionPageVO result = service.list(1L, null, null, "lt70", 1, 10);

        assertThat(result.getItems().get(0).getConfidence()).isEqualTo(60);
        verify(questionMapper).selectTeacherPage(1L, null, null, "lt70", 0, 10);
    }

    @Test
    void listPaginationBoundary() {
        when(questionMapper.selectTeacherPage(eq(1L), isNull(), isNull(), isNull(), eq(40), eq(10)))
                .thenReturn(Collections.emptyList());
        when(questionMapper.countTeacherFiltered(eq(1L), isNull(), isNull(), isNull())).thenReturn(0L);
        when(questionMapper.countGroupedByStatus(1L)).thenReturn(Collections.emptyList());

        TeacherQuestionPageVO result = service.list(1L, null, null, null, 5, 10);

        assertThat(result.getTotal()).isEqualTo(0L);
        assertThat(result.getItems()).isEmpty();
        assertThat(result.getPage()).isEqualTo(5);
        verify(questionMapper).selectTeacherPage(1L, null, null, null, 40, 10);
    }

    @Test
    void listValidatesInvalidStatus() {
        assertThatThrownBy(() -> service.list(1L, 3, null, null, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status 无效");

        assertThatThrownBy(() -> service.list(1L, -1, null, null, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status 无效");
    }

    @Test
    void listValidatesInvalidConf() {
        assertThatThrownBy(() -> service.list(1L, null, null, "invalid", 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conf 参数无效");
    }

    @Test
    @DisplayName("状态计数走 GROUP BY，不 selectList 全课")
    void listUsesGroupedStatusCountsNotFullSelectList() {
        when(questionMapper.selectTeacherPage(anyLong(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(questionMapper.countTeacherFiltered(anyLong(), any(), any(), any())).thenReturn(0L);
        stubStatusCounts();

        TeacherQuestionPageVO result = service.list(1L, null, null, null, 1, 10);

        assertThat(result.getCounts().getPending()).isEqualTo(1L);
        verify(questionMapper).countGroupedByStatus(1L);
        verify(questionMapper, never()).selectList(any());
    }
}
