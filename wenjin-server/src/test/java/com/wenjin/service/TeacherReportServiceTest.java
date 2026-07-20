package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.AgentReportVO;
import com.wenjin.entity.AgentReport;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.impl.TeacherReportServiceImpl;
import com.wenjin.service.multiagent.AgentReportService;
import com.wenjin.support.AgentReportKind;
import com.wenjin.support.AgentReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherReportServiceTest {

    @Mock AgentReportService agentReportService;
    @Mock CourseAccessService courseAccessService;

    private FeatureFlagsProperties flags;
    private TeacherReportServiceImpl service;

    @BeforeEach
    void setUp() {
        flags = new FeatureFlagsProperties();
        flags.setMultiAgentEnabled(true);
        service = new TeacherReportServiceImpl(agentReportService, courseAccessService, flags);
    }

    @Test
    @DisplayName("toggle off → 404 without access check")
    void disabled_404() {
        flags.setMultiAgentEnabled(false);
        assertThatThrownBy(() -> service.list(1L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.NOT_FOUND.getCode());
        verify(courseAccessService, never()).assertManageCourse(1L);
    }

    @Test
    @DisplayName("list requires manage course")
    void list_manageCourse() {
        AgentReport row = sample(11L, 1L, AgentReportKind.TEACHER_WEEKLY);
        when(agentReportService.listByCourseAndKind(1L, null)).thenReturn(List.of(row));

        List<AgentReportVO> list = service.list(1L, null);

        verify(courseAccessService).assertManageCourse(1L);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getId()).isEqualTo(11L);
        assertThat(list.get(0).getPayload().get("weakNodes").isArray()).isTrue();
    }

    @Test
    @DisplayName("getById asserts manage on report course")
    void getById_manageCourse() {
        AgentReport row = sample(22L, 5L, AgentReportKind.SUPERVISOR_SAMPLE);
        when(agentReportService.findById(22L)).thenReturn(row);

        AgentReportVO vo = service.getById(22L);
        verify(courseAccessService).assertManageCourse(5L);
        assertThat(vo.getKind()).isEqualTo(AgentReportKind.SUPERVISOR_SAMPLE);
    }

    @Test
    @DisplayName("getById missing → 404")
    void getById_missing() {
        when(agentReportService.findById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("cross-course manage failure → 404")
    void getById_accessDenied() {
        AgentReport row = sample(3L, 9L, AgentReportKind.TEACHER_WEEKLY);
        when(agentReportService.findById(3L)).thenReturn(row);
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "资源不存在"))
                .when(courseAccessService).assertManageCourse(9L);

        assertThatThrownBy(() -> service.getById(3L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    private static AgentReport sample(long id, long courseId, int kind) {
        AgentReport r = new AgentReport();
        r.setId(id);
        r.setCourseId(courseId);
        r.setKind(kind);
        r.setPeriodStart(LocalDate.of(2026, 7, 13));
        r.setPeriodEnd(LocalDate.of(2026, 7, 19));
        r.setStatus(AgentReportStatus.READY);
        r.setPayloadJson("{\"weakNodes\":[],\"anomalies\":[],\"engagement\":{},\"narrative\":\"\"}");
        return r;
    }
}
