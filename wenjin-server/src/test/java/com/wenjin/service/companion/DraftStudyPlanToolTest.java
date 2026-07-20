package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.DraftPlanResultVO;
import com.wenjin.service.plan.PlanService;
import com.wenjin.support.plan.PlanErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DraftStudyPlanToolTest {

    @Mock PlanService planService;
    private DraftStudyPlanTool tool;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setPlanEnabled(true);
        tool = new DraftStudyPlanTool(planService, flags, new LimitsProperties(), om);
    }

    @Test
    @DisplayName("sideEffecting=true 且 schema 含 draft_study_plan")
    void sideEffectingAndSchema() {
        assertThat(tool.sideEffecting()).isTrue();
        assertThat(tool.name()).isEqualTo("draft_study_plan");
        assertThat(tool.schema().get("function")).isNotNull();
    }

    @Test
    @DisplayName("成功 draft 返回 planId/taskCount")
    void execute_ok() throws Exception {
        DraftPlanResultVO vo = new DraftPlanResultVO();
        vo.setPlanId(42L);
        vo.setDays(7);
        vo.setTaskCount(5);
        when(planService.draft(eq(1L), eq(2L), any(LocalDate.class), any(), isNull()))
                .thenReturn(vo);

        ObjectNode args = om.createObjectNode();
        args.put("targetDate", LocalDate.now().plusDays(10).toString());
        String json = tool.execute(new ToolContext(1L, 2L, null), args);
        assertThat(json).contains("\"planId\":42");
        assertThat(json).contains("\"taskCount\":5");
        assertThat(json).contains("\"ok\":true");
    }

    @Test
    @DisplayName("溢出返回 PLAN_SCOPE_TOO_LARGE + suggestedScope")
    void execute_tooLarge() throws Exception {
        DraftPlanResultVO vo = new DraftPlanResultVO();
        vo.setErrorCode(PlanErrorCodes.PLAN_SCOPE_TOO_LARGE);
        vo.setSuggestedScope(List.of("A", "B"));
        when(planService.draft(anyLong(), anyLong(), any(), any(), any())).thenReturn(vo);

        ObjectNode args = om.createObjectNode();
        args.put("targetDate", LocalDate.now().plusDays(10).toString());
        String json = tool.execute(new ToolContext(1L, 2L, null), args);
        assertThat(json).contains(PlanErrorCodes.PLAN_SCOPE_TOO_LARGE);
        assertThat(json).contains("suggestedScope");
        assertThat(json).contains("\"ok\":false");
    }
}
