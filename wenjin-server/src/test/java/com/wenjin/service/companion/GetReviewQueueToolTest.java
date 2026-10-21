package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.dto.ReviewTodayVO.ReviewItemVO;
import com.wenjin.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetReviewQueueToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReviewService reviewService = mock(ReviewService.class);

    private GetReviewQueueTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetReviewQueueTool(reviewService, objectMapper);
    }

    @Test
    void returnsTodayQueueItems() throws Exception {
        ReviewItemVO item = new ReviewItemVO();
        item.setNodeCode("KT01");
        item.setName("Loops");
        item.setEffectiveScore(60.0);
        item.setUrgency(12.0);
        item.setForced(false);
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
    void reportsForcedPinsFromReviewService() throws Exception {
        ReviewItemVO base = new ReviewItemVO();
        base.setNodeCode("KT01");
        base.setName("Loops");
        base.setForced(false);

        ReviewItemVO forced = new ReviewItemVO();
        forced.setNodeCode("KT09");
        forced.setName("Forced Node");
        forced.setForced(true);
        forced.setPinMode("ADVANCE");

        ReviewTodayVO vo = new ReviewTodayVO();
        vo.setItems(List.of(forced, base));
        vo.setTotalDecayingCount(2);
        when(reviewService.getToday(1L, 10L)).thenReturn(vo);

        JsonNode json = objectMapper.readTree(tool.execute(new ToolContext(1L, 10L), objectMapper.createObjectNode()));

        assertThat(json.path("items")).hasSize(2);
        assertThat(json.at("/items/0/nodeCode").asText()).isEqualTo("KT09");
        assertThat(json.at("/items/0/forced").asBoolean()).isTrue();
        assertThat(json.at("/items/0/mode").asText()).isEqualTo("ADVANCE");
        assertThat(json.path("forcedCount").asInt()).isEqualTo(1);
    }
}
