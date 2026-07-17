package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.dto.ReviewTodayVO.ReviewItemVO;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetReviewQueueToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReviewService reviewService = mock(ReviewService.class);
    private final ReviewScheduleStore scheduleStore = new ReviewScheduleStore();
    private final KgNodeMapper nodeMapper = mock(KgNodeMapper.class);
    private final StudentMasteryMapper masteryMapper = mock(StudentMasteryMapper.class);
    private final MasteryDecayCalculator decayCalculator = mock(MasteryDecayCalculator.class);

    private GetReviewQueueTool tool;

    @BeforeEach
    void setUp() {
        scheduleStore.clear();
        tool = new GetReviewQueueTool(
                reviewService, scheduleStore, nodeMapper, masteryMapper, decayCalculator, objectMapper);
    }

    @Test
    void returnsTodayQueueItems() throws Exception {
        ReviewItemVO item = new ReviewItemVO();
        item.setNodeCode("KT01");
        item.setName("Loops");
        item.setEffectiveScore(60.0);
        item.setUrgency(12.0);
        ReviewTodayVO vo = new ReviewTodayVO();
        vo.setItems(List.of(item));
        vo.setTotalDecayingCount(3);
        when(reviewService.getToday(1L, 10L)).thenReturn(vo);

        JsonNode json = objectMapper.readTree(tool.execute(new ToolContext(1L, 10L), objectMapper.createObjectNode()));

        assertThat(json.path("items")).hasSize(1);
        assertThat(json.at("/items/0/nodeCode").asText()).isEqualTo("KT01");
        assertThat(json.at("/items/0/forced").asBoolean()).isFalse();
        assertThat(json.path("totalDecayingCount").asInt()).isEqualTo(3);
        assertThat(json.path("forcedCount").asInt()).isZero();
    }

    @Test
    void mergesForcedPinsNotAlreadyInQueue() throws Exception {
        ReviewItemVO item = new ReviewItemVO();
        item.setNodeCode("KT01");
        item.setName("Loops");
        ReviewTodayVO vo = new ReviewTodayVO();
        vo.setItems(List.of(item));
        vo.setTotalDecayingCount(1);
        when(reviewService.getToday(1L, 10L)).thenReturn(vo);

        scheduleStore.pin(1L, 10L, "KT01", ReviewScheduleStore.Mode.TODAY); // already present
        scheduleStore.pin(1L, 10L, "KT09", ReviewScheduleStore.Mode.ADVANCE);

        KgNode forced = new KgNode();
        forced.setId(99L);
        forced.setCourseId(10L);
        forced.setNodeCode("KT09");
        forced.setName("Forced Node");
        when(nodeMapper.selectOne(any())).thenReturn(forced);
        when(masteryMapper.selectOne(any())).thenReturn(null);

        JsonNode json = objectMapper.readTree(tool.execute(new ToolContext(1L, 10L), objectMapper.createObjectNode()));

        assertThat(json.path("items")).hasSize(2);
        assertThat(json.at("/items/0/nodeCode").asText()).isEqualTo("KT01");
        assertThat(json.at("/items/1/nodeCode").asText()).isEqualTo("KT09");
        assertThat(json.at("/items/1/forced").asBoolean()).isTrue();
        assertThat(json.at("/items/1/mode").asText()).isEqualTo("ADVANCE");
        assertThat(json.path("forcedCount").asInt()).isEqualTo(2);
    }
}
