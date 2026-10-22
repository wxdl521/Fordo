package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.AgentRunPageVO;
import com.wenjin.dto.AgentStepVO;
import com.wenjin.dto.RunStepCount;
import com.wenjin.entity.AgentRun;
import com.wenjin.entity.AgentStep;
import com.wenjin.mapper.AgentRunMapper;
import com.wenjin.mapper.AgentStepMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.impl.TeacherAgentRunServiceImpl;
import com.wenjin.support.AgentRunStatus;
import com.wenjin.support.AgentStepStatus;
import com.wenjin.support.AgentStepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherAgentRunServiceTest {

    @Mock AgentRunMapper agentRunMapper;
    @Mock AgentStepMapper agentStepMapper;
    @Mock CourseAccessService courseAccessService;

    private FeatureFlagsProperties featureFlags;
    private TeacherAgentRunServiceImpl service;

    @BeforeEach
    void setUp() {
        featureFlags = new FeatureFlagsProperties();
        featureFlags.setAgentTraceEnabled(true);
        service = new TeacherAgentRunServiceImpl(
                agentRunMapper, agentStepMapper, courseAccessService, featureFlags);
    }

    @Test
    @DisplayName("manage course ok: list runs with step counts, ordered desc")
    void listRuns_manageCourseOk() {
        AgentRun run = sampleRun(11L, 1L, 100L);
        when(agentRunMapper.selectCount(any())).thenReturn(1L);
        when(agentRunMapper.selectList(any())).thenReturn(List.of(run));
        RunStepCount count = new RunStepCount();
        count.setRunId(11L);
        count.setStepCount(3);
        when(agentStepMapper.countStepsByRunIds(List.of(11L))).thenReturn(List.of(count));

        AgentRunPageVO page = service.listRuns(1L, null, 1, 20);

        verify(courseAccessService).assertManageCourse(1L);
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(20);
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getId()).isEqualTo(11L);
        assertThat(page.getItems().get(0).getKind()).isEqualTo("COMPANION_CHAT");
        assertThat(page.getItems().get(0).getStepCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("optional studentId filter is applied")
    void listRuns_filtersStudentId() {
        when(agentRunMapper.selectCount(any())).thenReturn(0L);

        service.listRuns(1L, 55L, 1, 10);

        verify(courseAccessService).assertManageCourse(1L);
        var w = capturedCountWrapper();
        assertThat(w.getSqlSegment()).contains("student_id =");
        assertThat(w.getParamNameValuePairs().values()).contains(55L);
        // total=0 时不拉 list
        verify(agentRunMapper, never()).selectList(any());
        verify(agentStepMapper, never()).countStepsByRunIds(any());
    }

    @Test
    @DisplayName("wrong course (no manage) → 404，不查库列表")
    void listRuns_wrongCourse404() {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "资源不存在"))
                .when(courseAccessService).assertManageCourse(99L);

        assertThatThrownBy(() -> service.listRuns(99L, null, 1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());

        verify(agentRunMapper, never()).selectCount(any());
        verify(agentRunMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("size 超过 50 夹到 50；≤0 用默认 20")
    void listRuns_sizeClamped() {
        when(agentRunMapper.selectCount(any())).thenReturn(0L);

        AgentRunPageVO over = service.listRuns(1L, null, 1, 999);
        assertThat(over.getSize()).isEqualTo(TeacherAgentRunServiceImpl.MAX_PAGE_SIZE);

        AgentRunPageVO zero = service.listRuns(1L, null, 1, 0);
        assertThat(zero.getSize()).isEqualTo(TeacherAgentRunServiceImpl.DEFAULT_PAGE_SIZE);

        assertThat(TeacherAgentRunServiceImpl.clampSize(50)).isEqualTo(50);
        assertThat(TeacherAgentRunServiceImpl.clampSize(51)).isEqualTo(50);
        assertThat(TeacherAgentRunServiceImpl.clampSize(-3)).isEqualTo(20);
    }

    @Test
    @DisplayName("toggle off → list 404")
    void listRuns_toggleOff404() {
        featureFlags.setAgentTraceEnabled(false);

        assertThatThrownBy(() -> service.listRuns(1L, null, 1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());

        verify(courseAccessService, never()).assertManageCourse(any());
        verify(agentRunMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("steps: owned run returns ordered steps")
    void listSteps_ok() {
        AgentRun run = sampleRun(11L, 1L, 100L);
        when(agentRunMapper.selectById(11L)).thenReturn(run);
        AgentStep s1 = sampleStep(1L, 11L, 1, "phase1");
        AgentStep s2 = sampleStep(2L, 11L, 2, "query_mastery");
        when(agentStepMapper.selectList(any())).thenReturn(List.of(s1, s2));

        List<AgentStepVO> steps = service.listSteps(11L);

        verify(courseAccessService).assertManageCourse(1L);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getSeq()).isEqualTo(1);
        assertThat(steps.get(0).getName()).isEqualTo("phase1");
        assertThat(steps.get(1).getStepType()).isEqualTo(AgentStepType.LLM_CALL);
    }

    @Test
    @DisplayName("steps for foreign run (other course) → 404")
    void listSteps_foreignRun404() {
        AgentRun run = sampleRun(11L, 2L, 100L);
        when(agentRunMapper.selectById(11L)).thenReturn(run);
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "资源不存在"))
                .when(courseAccessService).assertManageCourse(2L);

        assertThatThrownBy(() -> service.listSteps(11L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());

        verify(agentStepMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("steps for missing run → 404")
    void listSteps_missingRun404() {
        when(agentRunMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.listSteps(404L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());

        verify(courseAccessService, never()).assertManageCourse(any());
    }

    @Test
    @DisplayName("toggle off → steps 404")
    void listSteps_toggleOff404() {
        featureFlags.setAgentTraceEnabled(false);

        assertThatThrownBy(() -> service.listSteps(11L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());

        verify(agentRunMapper, never()).selectById(eq(11L));
    }

    private static AgentRun sampleRun(Long id, Long courseId, Long studentId) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setCourseId(courseId);
        run.setStudentId(studentId);
        run.setKind("COMPANION_CHAT");
        run.setStatus(AgentRunStatus.SUCCEEDED);
        run.setModel("gpt-test");
        run.setTokensIn(10);
        run.setTokensOut(20);
        run.setDurationMs(100);
        run.setCreatedAt(LocalDateTime.of(2026, 7, 17, 12, 0));
        run.setFinishedAt(LocalDateTime.of(2026, 7, 17, 12, 0, 1));
        return run;
    }

    private static AgentStep sampleStep(Long id, Long runId, int seq, String name) {
        AgentStep step = new AgentStep();
        step.setId(id);
        step.setRunId(runId);
        step.setSeq(seq);
        step.setStepType(AgentStepType.LLM_CALL);
        step.setName(name);
        step.setStatus(AgentStepStatus.OK);
        step.setTokensIn(1);
        step.setTokensOut(2);
        step.setCreatedAt(LocalDateTime.of(2026, 7, 17, 12, 0, seq));
        return step;
    }

    // ── 方向 7：kind 过滤，默认排除 TEACHER_CHAT ──

    @SuppressWarnings("unchecked")
    private com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AgentRun> capturedCountWrapper() {
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AgentRun>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(agentRunMapper).selectCount(captor.capture());
        captor.getValue().getSqlSegment(); // 参数表在生成 SQL 片段时填充
        return captor.getValue();
    }

    @Test
    @DisplayName("默认（不传 kind）排除 TEACHER_CHAT")
    void listRuns_defaultExcludesTeacherChat() {
        when(agentRunMapper.selectCount(any())).thenReturn(0L);

        service.listRuns(1L, null, null, 1, 20);

        var w = capturedCountWrapper();
        assertThat(w.getSqlSegment()).contains("kind <>");
        assertThat(w.getParamNameValuePairs().values()).contains("TEACHER_CHAT");
    }

    @Test
    @DisplayName("kind=COMPANION_CHAT：精确过滤")
    void listRuns_kindFilter() {
        when(agentRunMapper.selectCount(any())).thenReturn(0L);

        service.listRuns(1L, null, "COMPANION_CHAT", 1, 20);

        var w = capturedCountWrapper();
        assertThat(w.getSqlSegment()).contains("kind =").doesNotContain("kind <>");
        assertThat(w.getParamNameValuePairs().values()).contains("COMPANION_CHAT");
    }

    @Test
    @DisplayName("kind=TEACHER_CHAT：只看自己的助教轨迹（student_id 列 = 当前老师）")
    void listRuns_teacherChatOnlyOwn() {
        when(agentRunMapper.selectCount(any())).thenReturn(0L);
        com.wenjin.config.CurrentUser.set(new com.wenjin.security.CurrentPrincipal(9L, com.wenjin.security.UserRole.TEACHER));
        try {
            service.listRuns(1L, 55L, "TEACHER_CHAT", 1, 20);
        } finally {
            com.wenjin.config.CurrentUser.clear();
        }

        var w = capturedCountWrapper();
        assertThat(w.getParamNameValuePairs().values()).contains("TEACHER_CHAT", 9L).doesNotContain(55L);
    }

    @Test
    @DisplayName("listSteps：他人的 TEACHER_CHAT run → 404（即便课程可管理）")
    void listSteps_foreignTeacherChat404() {
        AgentRun run = sampleRun(11L, 1L, 100L);
        run.setKind("TEACHER_CHAT");
        when(agentRunMapper.selectById(11L)).thenReturn(run);
        com.wenjin.config.CurrentUser.set(new com.wenjin.security.CurrentPrincipal(9L, com.wenjin.security.UserRole.TEACHER));
        try {
            assertThatThrownBy(() -> service.listSteps(11L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ResultCode.NOT_FOUND.getCode());
            run.setStudentId(9L);
            when(agentStepMapper.selectList(any())).thenReturn(List.of());
            assertThat(service.listSteps(11L)).isEmpty();
        } finally {
            com.wenjin.config.CurrentUser.clear();
        }
    }
}
