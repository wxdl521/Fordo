package com.wenjin.service;

import com.wenjin.dto.ReviewAllRequest;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.impl.TeacherQuestionServiceImpl;
import com.wenjin.support.QuestionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * reviewAll：筛选谓词下推 UPDATE / 分批 ≤500。
 */
@ExtendWith(MockitoExtension.class)
class ReviewAllTest {

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

    @BeforeEach
    void setUp() {
        lenient().doNothing().when(courseAccessService).assertManageCourse(anyLong());
    }

    @Test
    @DisplayName("ownership assert 先于写库")
    void assertManageCourseFirst() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setStatus(QuestionStatus.PENDING);
        req.setAction("pass");
        when(questionMapper.selectIdsByFilter(eq(1L), eq(0), isNull(), isNull(), eq(500)))
                .thenReturn(Collections.emptyList());

        service.reviewAll(1L, req);

        InOrder order = inOrder(courseAccessService, questionMapper);
        order.verify(courseAccessService).assertManageCourse(1L);
        order.verify(questionMapper).selectIdsByFilter(anyLong(), any(), any(), any(), anyInt());
    }

    /** courseId+status=0 全量 pass → 分批更新 */
    @Test
    void reviewAllPassPendingQuestions() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setStatus(QuestionStatus.PENDING);
        req.setAction("pass");

        when(questionMapper.selectIdsByFilter(eq(1L), eq(0), isNull(), isNull(), eq(500)))
                .thenReturn(Arrays.asList(101L));
        when(questionMapper.updateStatusByIds(eq(1L), eq(Arrays.asList(101L)), eq(QuestionStatus.APPROVED)))
                .thenReturn(1);

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(1);
        verify(questionMapper).updateStatusByIds(1L, Arrays.asList(101L), QuestionStatus.APPROVED);
        verify(questionMapper, never()).updateStatusByFilter(anyLong(), anyInt(), any(), any(), any());
    }

    /** 带 nodeCode 过滤 → 谓词带 nodeCode */
    @Test
    void reviewAllWithNodeCodeFilterOnlyUpdatesMatchedQuestions() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setStatus(QuestionStatus.PENDING);
        req.setNodeCode("N001");
        req.setAction("pass");

        when(questionMapper.selectIdsByFilter(eq(1L), eq(0), eq("N001"), isNull(), eq(500)))
                .thenReturn(Arrays.asList(101L));
        when(questionMapper.updateStatusByIds(eq(1L), anyList(), eq(QuestionStatus.APPROVED)))
                .thenReturn(1);

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(1);
        verify(questionMapper).selectIdsByFilter(1L, 0, "N001", null, 500);
    }

    /** nodeCode 无匹配 → 返回 0 */
    @Test
    void reviewAllReturnsZeroWhenNodeCodeHasNoMatchingNode() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setStatus(QuestionStatus.PENDING);
        req.setNodeCode("N999");
        req.setAction("pass");

        when(questionMapper.selectIdsByFilter(eq(1L), eq(0), eq("N999"), isNull(), eq(500)))
                .thenReturn(Collections.emptyList());

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(0);
        verify(questionMapper, never()).updateStatusByIds(anyLong(), anyList(), anyInt());
    }

    /** 无 status 筛选 → 单次谓词 UPDATE（避免分批死循环） */
    @Test
    void reviewAllWithoutStatusUsesSinglePredicateUpdate() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setAction("pass");

        when(questionMapper.updateStatusByFilter(eq(1L), eq(QuestionStatus.APPROVED),
                isNull(), isNull(), isNull())).thenReturn(3);

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(3);
        verify(questionMapper).updateStatusByFilter(1L, QuestionStatus.APPROVED, null, null, null);
        verify(questionMapper, never()).selectIdsByFilter(anyLong(), any(), any(), any(), anyInt());
    }

    /** 筛选结果为空 → 0 */
    @Test
    void reviewAllReturnsZeroWhenNoMatchingQuestions() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setStatus(QuestionStatus.PENDING);
        req.setAction("pass");

        when(questionMapper.selectIdsByFilter(eq(1L), eq(0), isNull(), isNull(), eq(500)))
                .thenReturn(Collections.emptyList());

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(0);
        verify(questionMapper, never()).updateStatusByIds(anyLong(), anyList(), anyInt());
    }

    /** reject 动作 */
    @Test
    void reviewAllRejectActionCallsUpdate() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setStatus(QuestionStatus.PENDING);
        req.setAction("reject");

        when(questionMapper.selectIdsByFilter(eq(1L), eq(0), isNull(), isNull(), eq(500)))
                .thenReturn(Arrays.asList(101L));
        when(questionMapper.updateStatusByIds(eq(1L), anyList(), eq(QuestionStatus.REJECTED)))
                .thenReturn(1);

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(1);
        verify(questionMapper).updateStatusByIds(1L, Arrays.asList(101L), QuestionStatus.REJECTED);
    }

    /** 超过 500 条时分批 */
    @Test
    void reviewAllBatchesAtMost500() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setStatus(QuestionStatus.PENDING);
        req.setAction("pass");

        Long[] first = new Long[500];
        for (int i = 0; i < 500; i++) {
            first[i] = (long) (i + 1);
        }
        when(questionMapper.selectIdsByFilter(eq(1L), eq(0), isNull(), isNull(), eq(500)))
                .thenReturn(Arrays.asList(first))
                .thenReturn(Arrays.asList(501L, 502L));
        when(questionMapper.updateStatusByIds(eq(1L), anyList(), eq(QuestionStatus.APPROVED)))
                .thenReturn(500)
                .thenReturn(2);

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(502);
        verify(questionMapper, times(2)).selectIdsByFilter(1L, 0, null, null, 500);
        verify(questionMapper, times(2)).updateStatusByIds(eq(1L), anyList(), eq(QuestionStatus.APPROVED));
    }

    @Test
    void reviewAllInvalidCourseIdThrows() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setAction("pass");

        assertThatThrownBy(() -> service.reviewAll(null, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("courseId");

        assertThatThrownBy(() -> service.reviewAll(0L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("courseId");
    }

    @Test
    void reviewAllInvalidActionThrows() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setAction("approve");

        assertThatThrownBy(() -> service.reviewAll(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    @Test
    void reviewAllNullRequestThrows() {
        assertThatThrownBy(() -> service.reviewAll(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request");
    }
}
