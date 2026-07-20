package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.PlanVO;
import com.wenjin.service.plan.PlanService;
import com.wenjin.support.plan.PlanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetPlanProgressToolTest {

    @Mock PlanService planService;

    @Test
    @DisplayName("无计划返回 hasPlan=false 空态")
    void emptyState() throws Exception {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setPlanEnabled(true);
        GetPlanProgressTool tool = new GetPlanProgressTool(planService, flags, new ObjectMapper());
        when(planService.progress(1L, 2L)).thenReturn(new PlanVO());

        String json = tool.execute(new ToolContext(1L, 2L, null), null);
        assertThat(json).contains("\"hasPlan\":false");
        assertThat(json).contains("当前没有学习计划");
        assertThat(tool.sideEffecting()).isFalse();
    }

    @Test
    @DisplayName("有 ACTIVE 计划返回 todayRemaining")
    void withPlan() throws Exception {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setPlanEnabled(true);
        GetPlanProgressTool tool = new GetPlanProgressTool(planService, flags, new ObjectMapper());
        PlanVO vo = new PlanVO();
        vo.setId(9L);
        vo.setStatus(PlanStatus.ACTIVE);
        vo.setTaskCount(10);
        vo.setDoneCount(3);
        when(planService.progress(1L, 2L)).thenReturn(vo);

        String json = tool.execute(new ToolContext(1L, 2L, null), null);
        assertThat(json).contains("\"hasPlan\":true");
        assertThat(json).contains("\"planId\":9");
        assertThat(json).contains("ACTIVE");
    }
}
