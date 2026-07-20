package com.wenjin.service.companion;

import com.wenjin.dto.PracticeStartVO;
import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.PracticeService;
import com.wenjin.service.ReviewService;
import com.wenjin.service.trace.TraceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanionDegradeServiceTest {

    @Mock KgNodeMapper nodeMapper;
    @Mock StudentMasteryMapper masteryMapper;
    @Mock MasteryDecayCalculator decayCalculator;
    @Mock ReviewService reviewService;
    @Mock PracticeService practiceService;

    private CompanionDegradeService service;

    @BeforeEach
    void setUp() {
        service = new CompanionDegradeService(
                nodeMapper, masteryMapper, decayCalculator, reviewService, practiceService, 75.0);
    }

    @Test
    @DisplayName("due review preferred over weak")
    void pickDueFirst() {
        ReviewTodayVO.ReviewItemVO item = new ReviewTodayVO.ReviewItemVO();
        item.setNodeCode("SE-2.1");
        ReviewTodayVO today = new ReviewTodayVO();
        today.setItems(List.of(item));
        when(reviewService.getToday(1L, 10L)).thenReturn(today);

        KgNode node = new KgNode();
        node.setId(21L);
        node.setNodeCode("SE-2.1");
        node.setName("过程模型");
        when(nodeMapper.selectOne(any())).thenReturn(node);

        PracticeStartVO card = new PracticeStartVO();
        card.setSessionId(1L);
        PracticeStartVO.NodeRef ref = new PracticeStartVO.NodeRef();
        ref.setNodeCode("SE-2.1");
        ref.setName("过程模型");
        card.setNode(ref);
        when(practiceService.start(eq(1L), eq(10L), eq(21L), isNull(), eq(2))).thenReturn(card);

        CompanionDegradeService.DegradeResult result =
                service.degrade(1L, 10L, "SE-3.2", CompanionDegradeService.REASON_LLM_DISABLED,
                        TraceContext.NOOP);

        assertThat(result.reason()).isEqualTo(CompanionDegradeService.REASON_LLM_DISABLED);
        assertThat(result.text()).contains("无法调用大模型");
        assertThat(result.practiceCard()).isSameAs(card);
        verify(practiceService).start(1L, 10L, 21L, null, 2);
    }

    @Test
    @DisplayName("no due → weak mastery")
    void pickWeak() {
        when(reviewService.getToday(1L, 10L)).thenReturn(new ReviewTodayVO());

        StudentMastery m = new StudentMastery();
        m.setNodeId(32L);
        m.setMasteryScore(BigDecimal.valueOf(30));
        when(masteryMapper.selectList(any())).thenReturn(List.of(m));
        when(decayCalculator.effectiveScoreOf(m)).thenReturn(28.0);

        KgNode node = new KgNode();
        node.setId(32L);
        node.setNodeCode("SE-3.2");
        node.setName("用例图");
        when(nodeMapper.selectById(32L)).thenReturn(node);

        var pick = service.pickPracticeTarget(1L, 10L, null);
        assertThat(pick).isPresent();
        assertThat(pick.get().tier()).isEqualTo("WEAK");
        assertThat(pick.get().nodeCode()).isEqualTo("SE-3.2");
    }
}
