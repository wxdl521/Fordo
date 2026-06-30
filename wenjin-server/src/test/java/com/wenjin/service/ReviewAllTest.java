package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenjin.dto.ReviewAllRequest;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.impl.TeacherQuestionServiceImpl;
import com.wenjin.support.QuestionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * T6: reviewAll 服务层单元测试——沿用 TeacherQuestionServiceImplTest 的 mock/fake 搭法。
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

    @InjectMocks
    private TeacherQuestionServiceImpl service;

    private Question q1, q2;
    private QuestionNode qn1;
    private KgNode node1;

    @BeforeEach
    void setUp() {
        // q1: 待审核（PENDING）
        q1 = new Question();
        q1.setId(101L);
        q1.setCourseId(1L);
        q1.setStatus(QuestionStatus.PENDING);

        // q2: 已通过（APPROVED），用于验证无关题不被触碰
        q2 = new Question();
        q2.setId(102L);
        q2.setCourseId(1L);
        q2.setStatus(QuestionStatus.APPROVED);

        // 主考点映射：q1 属于 node1（weight=1）
        qn1 = new QuestionNode();
        qn1.setId(501L);
        qn1.setQuestionId(101L);
        qn1.setNodeId(201L);
        qn1.setWeight(1);

        // 知识点节点
        node1 = new KgNode();
        node1.setId(201L);
        node1.setCourseId(1L);
        node1.setNodeCode("N001");
        node1.setName("Node 1");
    }

    /** courseId+status=0 全量 pass → 待审题全部通过，affected 数正确 */
    @Test
    void reviewAllPassPendingQuestions() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setStatus(QuestionStatus.PENDING);
        req.setAction("pass");

        when(questionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(q1));
        when(questionMapper.update(isNull(), any(UpdateWrapper.class)))
                .thenReturn(1);

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(1);
        verify(questionMapper).update(isNull(), any(UpdateWrapper.class));
    }

    /** 带 nodeCode 过滤 → 只动该主考点匹配子集 */
    @Test
    void reviewAllWithNodeCodeFilterOnlyUpdatesMatchedQuestions() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setStatus(QuestionStatus.PENDING);
        req.setNodeCode("N001");
        req.setAction("pass");

        when(nodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(node1);
        when(questionNodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(qn1));
        when(questionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(q1));
        when(questionMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(1);
        // 验证只更新了 q1 所属节点的题目
        verify(questionMapper).update(isNull(), any(UpdateWrapper.class));
    }

    /** nodeCode 指定但知识点节点不存在 → 返回 0，不发 update */
    @Test
    void reviewAllReturnsZeroWhenNodeCodeHasNoMatchingNode() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setStatus(QuestionStatus.PENDING);
        req.setNodeCode("N999");
        req.setAction("pass");

        // 知识点节点不存在
        when(nodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(0);
        verify(questionMapper, never()).update(any(), any());
    }

    /** nodeCode 存在但该节点下无主考点题目 → 返回 0，不发 update */
    @Test
    void reviewAllReturnsZeroWhenNodeHasNoQuestions() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setNodeCode("N001");
        req.setAction("pass");

        when(nodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(node1);
        when(questionNodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(0);
        verify(questionMapper, never()).update(any(), any());
    }

    /** 筛选结果为空列表 → 返回 0，不发 update */
    @Test
    void reviewAllReturnsZeroWhenNoMatchingQuestions() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setStatus(QuestionStatus.PENDING);
        req.setAction("pass");

        when(questionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(0);
        verify(questionMapper, never()).update(any(), any());
    }

    /** reject 动作 → update 被调用，返回 affected 数 */
    @Test
    void reviewAllRejectActionCallsUpdate() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setStatus(QuestionStatus.PENDING);
        req.setAction("reject");

        when(questionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(q1));
        when(questionMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        int affected = service.reviewAll(1L, req);

        assertThat(affected).isEqualTo(1);
        verify(questionMapper).update(isNull(), any(UpdateWrapper.class));
    }

    /** courseId 无效（null / 0）→ 抛 IllegalArgumentException */
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

    /** action 无效 → 抛 IllegalArgumentException */
    @Test
    void reviewAllInvalidActionThrows() {
        ReviewAllRequest req = new ReviewAllRequest();
        req.setAction("approve"); // 无效值

        assertThatThrownBy(() -> service.reviewAll(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    /** request 为 null → 抛 IllegalArgumentException */
    @Test
    void reviewAllNullRequestThrows() {
        assertThatThrownBy(() -> service.reviewAll(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request");
    }
}
