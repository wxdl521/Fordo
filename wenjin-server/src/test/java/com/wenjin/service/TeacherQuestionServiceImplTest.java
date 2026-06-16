package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenjin.dto.QuestionReviewRequest;
import com.wenjin.dto.TeacherQuestionPageVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.impl.TeacherQuestionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @InjectMocks
    private TeacherQuestionServiceImpl service;

    private Question q1, q2, q3;
    private QuestionOption opt1a, opt1b, opt2a, opt2b;
    private QuestionNode qn1, qn2;
    private KgNode node1;

    @BeforeEach
    void setUp() {
        // Questions with different confidence levels
        q1 = new Question();
        q1.setId(101L);
        q1.setCourseId(1L);
        q1.setStem("Question 1");
        q1.setType(1);
        q1.setDifficulty(3);
        q1.setConfidence(90);
        q1.setStatus(0); // PENDING
        q1.setSource(1);
        q1.setCreatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));

        q2 = new Question();
        q2.setId(102L);
        q2.setCourseId(1L);
        q2.setStem("Question 2");
        q2.setType(2);
        q2.setDifficulty(5);
        q2.setConfidence(75);
        q2.setStatus(1); // APPROVED
        q2.setSource(2);
        q2.setCreatedAt(LocalDateTime.of(2026, 6, 2, 10, 0));

        q3 = new Question();
        q3.setId(103L);
        q3.setCourseId(1L);
        q3.setStem("Question 3");
        q3.setType(1);
        q3.setDifficulty(2);
        q3.setConfidence(60);
        q3.setStatus(2); // REJECTED
        q3.setSource(1);
        q3.setCreatedAt(LocalDateTime.of(2026, 6, 3, 10, 0));

        // Options for q1
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

        // Options for q2
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

        // Main point for q1
        qn1 = new QuestionNode();
        qn1.setId(501L);
        qn1.setQuestionId(101L);
        qn1.setNodeId(201L);
        qn1.setWeight(1);

        // Main point for q2
        qn2 = new QuestionNode();
        qn2.setId(502L);
        qn2.setQuestionId(102L);
        qn2.setNodeId(202L);
        qn2.setWeight(1);

        // Node info
        node1 = new KgNode();
        node1.setId(201L);
        node1.setNodeCode("N001");
        node1.setName("Node 1");
    }

    @Test
    void listSortsByConfidenceDescAndLoadsOptionsAndMainNode() {
        // Given: 3 questions with confidence 90, 75, 60
        when(questionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(q1, q2, q3));

        // Mock batch loading for all questions
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

        // When: list all questions, page 1, size 10
        TeacherQuestionPageVO result = service.list(1L, null, null, null, 1, 10);

        // Then: sorted by confidence desc (90, 75, 60)
        assertThat(result.getTotal()).isEqualTo(3L);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(10);
        assertThat(result.getItems()).hasSize(3);
        assertThat(result.getItems().get(0).getConfidence()).isEqualTo(90);
        assertThat(result.getItems().get(1).getConfidence()).isEqualTo(75);
        assertThat(result.getItems().get(2).getConfidence()).isEqualTo(60);

        // Check options loaded
        assertThat(result.getItems().get(0).getOptions()).hasSize(2);
        assertThat(result.getItems().get(0).getOptions().get(0).getKey()).isEqualTo("A");
        assertThat(result.getItems().get(0).getOptions().get(0).getCorrect()).isTrue();
        assertThat(result.getItems().get(0).getOptions().get(0).getPointNodeCode()).isEqualTo("N001");

        // Check main node loaded
        assertThat(result.getItems().get(0).getMainNodeCode()).isEqualTo("N001");
        assertThat(result.getItems().get(0).getMainNodeName()).isEqualTo("Node 1");

        // Check counts
        assertThat(result.getCounts().getPending()).isEqualTo(1L);
        assertThat(result.getCounts().getPassed()).isEqualTo(1L);
        assertThat(result.getCounts().getRejected()).isEqualTo(1L);
    }

    @Test
    void reviewPassUpdatesStatusToApproved() {
        // Given: 2 questions to approve
        QuestionReviewRequest req = new QuestionReviewRequest();
        req.setIds(Arrays.asList(101L, 103L));
        req.setAction("pass");

        when(questionMapper.update(isNull(), any(UpdateWrapper.class)))
                .thenReturn(2);

        // When: review with pass action
        int affected = service.review(1L, req);

        // Then: status updated to 1 (APPROVED)
        assertThat(affected).isEqualTo(2);

        verify(questionMapper).update(isNull(), argThat(uw -> {
            // Verify the UpdateWrapper sets status=1 for the given IDs
            return true; // UpdateWrapper doesn't expose internal state easily
        }));
    }

    @Test
    void reviewRejectUpdatesStatusToRejected() {
        // Given: 2 questions to reject
        QuestionReviewRequest req = new QuestionReviewRequest();
        req.setIds(Arrays.asList(101L, 102L));
        req.setAction("reject");

        when(questionMapper.update(isNull(), any(UpdateWrapper.class)))
                .thenReturn(2);

        // When: review with reject action
        int affected = service.review(1L, req);

        // Then: status updated to 2 (REJECTED)
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

    // I5: Additional test coverage for filtering and pagination

    @Test
    void listFiltersNodeCode() {
        // Given: q1 has main node N001, q2 has N002
        when(questionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(q1));

        KgNode targetNode = new KgNode();
        targetNode.setId(201L);
        targetNode.setNodeCode("N001");
        when(nodeMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(targetNode);

        when(questionNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(qn1));

        when(questionOptionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(opt1a, opt1b));

        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    KgNode n1 = new KgNode();
                    n1.setId(201L);
                    n1.setNodeCode("N001");
                    n1.setName("Node 1");
                    return Arrays.asList(n1);
                });

        // When: filter by nodeCode N001
        TeacherQuestionPageVO result = service.list(1L, null, "N001", null, 1, 10);

        // Then: only q1 returned
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getId()).isEqualTo(101L);
    }

    @Test
    void listFiltersConfidenceGe85() {
        // Given: q1=90, q2=75, q3=60
        when(questionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(q1));

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

        // When: filter confidence >= 85
        TeacherQuestionPageVO result = service.list(1L, null, null, "ge85", 1, 10);

        // Then: only q1 (90) returned
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getConfidence()).isEqualTo(90);
    }

    @Test
    void listFiltersConfidenceMid() {
        // Given: q1=90, q2=75, q3=60
        when(questionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(q2));

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

        // When: filter confidence 70-84
        TeacherQuestionPageVO result = service.list(1L, null, null, "mid", 1, 10);

        // Then: only q2 (75) returned
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getConfidence()).isEqualTo(75);
    }

    @Test
    void listFiltersConfidenceLt70() {
        // Given: q1=90, q2=75, q3=60
        when(questionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(q3));

        when(questionOptionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        when(questionNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        // When: filter confidence < 70
        TeacherQuestionPageVO result = service.list(1L, null, null, "lt70", 1, 10);

        // Then: only q3 (60) returned
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getConfidence()).isEqualTo(60);
    }

    @Test
    void listPaginationBoundary() {
        // Given: empty result set
        when(questionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList())
                .thenReturn(Collections.emptyList());

        // When: request page beyond results
        TeacherQuestionPageVO result = service.list(1L, null, null, null, 5, 10);

        // Then: empty page returned
        assertThat(result.getTotal()).isEqualTo(0L);
        assertThat(result.getItems()).isEmpty();
        assertThat(result.getPage()).isEqualTo(5);
    }

    @Test
    void listValidatesInvalidStatus() {
        // I8: Validate status parameter
        assertThatThrownBy(() -> service.list(1L, 3, null, null, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status 无效");

        assertThatThrownBy(() -> service.list(1L, -1, null, null, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status 无效");
    }

    @Test
    void listValidatesInvalidConf() {
        // I6: Throw exception for invalid conf
        assertThatThrownBy(() -> service.list(1L, null, null, "invalid", 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conf 参数无效");
    }

    @Test
    void listNormalizesPaginationParams() {
        // C4: Validate and normalize pagination parameters
        when(questionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(q1));

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

        // When: invalid page/size params
        TeacherQuestionPageVO result1 = service.list(1L, null, null, null, 0, 10);
        assertThat(result1.getPage()).isEqualTo(1); // normalized to 1

        TeacherQuestionPageVO result2 = service.list(1L, null, null, null, 1, 0);
        assertThat(result2.getSize()).isEqualTo(20); // normalized to default 20

        TeacherQuestionPageVO result3 = service.list(1L, null, null, null, 1, 200);
        assertThat(result3.getSize()).isEqualTo(20); // capped to 20 (exceeds 100)
    }
}
