package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.Result;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AgentReportVO;
import com.wenjin.service.TeacherReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeacherReportControllerTest {

    @Test
    @DisplayName("list delegates to service")
    void list_ok() {
        TeacherReportService fake = new TeacherReportService() {
            @Override
            public List<AgentReportVO> list(Long courseId, Integer kind) {
                AgentReportVO vo = new AgentReportVO();
                vo.setId(1L);
                vo.setCourseId(courseId);
                vo.setKind(kind);
                return List.of(vo);
            }

            @Override
            public AgentReportVO getById(Long id) {
                throw new UnsupportedOperationException();
            }
        };
        TeacherReportController controller = new TeacherReportController(fake);
        Result<List<AgentReportVO>> result = controller.list(7L, 0);
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getCourseId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("toggle off surfaces as NOT_FOUND")
    void disabled_404() {
        TeacherReportService fake = new TeacherReportService() {
            @Override
            public List<AgentReportVO> list(Long courseId, Integer kind) {
                throw new BusinessException(ResultCode.NOT_FOUND, "资源不存在");
            }

            @Override
            public AgentReportVO getById(Long id) {
                throw new BusinessException(ResultCode.NOT_FOUND, "资源不存在");
            }
        };
        TeacherReportController controller = new TeacherReportController(fake);
        assertThatThrownBy(() -> controller.list(1L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.NOT_FOUND.getCode());
        assertThatThrownBy(() -> controller.get(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.NOT_FOUND.getCode());
    }
}
