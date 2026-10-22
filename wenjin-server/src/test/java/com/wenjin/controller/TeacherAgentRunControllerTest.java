package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.Result;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AgentRunPageVO;
import com.wenjin.dto.AgentRunVO;
import com.wenjin.dto.AgentStepVO;
import com.wenjin.service.TeacherAgentRunService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 教师端 Agent 轨迹控制器：委托服务、参数透传（size 由服务层夹紧）。
 */
class TeacherAgentRunControllerTest {

    @Test
    @DisplayName("list: 透传 courseId/studentId/page/size")
    void list_delegatesParams() {
        AtomicLong course = new AtomicLong();
        AtomicReference<Long> student = new AtomicReference<>();
        AtomicInteger page = new AtomicInteger();
        AtomicInteger size = new AtomicInteger();

        AgentRunPageVO expected = new AgentRunPageVO();
        expected.setTotal(0);
        expected.setPage(1);
        expected.setSize(20);
        expected.setItems(List.of());

        TeacherAgentRunService fake = new TeacherAgentRunService() {
            @Override
            public AgentRunPageVO listRuns(Long courseId, Long studentId, int p, int s) {
                course.set(courseId);
                student.set(studentId);
                page.set(p);
                size.set(s);
                return expected;
            }

            @Override
            public List<AgentStepVO> listSteps(Long runId) {
                return List.of();
            }
        };
        TeacherAgentRunController controller = new TeacherAgentRunController(fake);

        Result<AgentRunPageVO> res = controller.list(7L, 55L, null, 2, 100);

        assertThat(course.get()).isEqualTo(7L);
        assertThat(student.get()).isEqualTo(55L);
        assertThat(page.get()).isEqualTo(2);
        assertThat(size.get()).isEqualTo(100); // 夹紧在 service
        assertThat(res.getData()).isSameAs(expected);
    }

    @Test
    @DisplayName("list: manage course ok 返回 items")
    void list_manageCourseOk() {
        AgentRunVO item = new AgentRunVO();
        item.setId(1L);
        item.setKind("COMPANION_CHAT");
        item.setStepCount(2);
        AgentRunPageVO page = new AgentRunPageVO();
        page.setTotal(1);
        page.setPage(1);
        page.setSize(20);
        page.setItems(List.of(item));

        TeacherAgentRunController controller = new TeacherAgentRunController(new TeacherAgentRunService() {
            @Override
            public AgentRunPageVO listRuns(Long courseId, Long studentId, int p, int s) {
                return page;
            }

            @Override
            public List<AgentStepVO> listSteps(Long runId) {
                return List.of();
            }
        });

        Result<AgentRunPageVO> res = controller.list(1L, null, null, 1, 20);
        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getData().getItems()).hasSize(1);
        assertThat(res.getData().getItems().get(0).getStepCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("list: 无管理权时服务抛 404 原样上抛")
    void list_wrongCourse404() {
        TeacherAgentRunController controller = new TeacherAgentRunController(new TeacherAgentRunService() {
            @Override
            public AgentRunPageVO listRuns(Long courseId, Long studentId, int p, int s) {
                throw new BusinessException(ResultCode.NOT_FOUND, "资源不存在");
            }

            @Override
            public List<AgentStepVO> listSteps(Long runId) {
                return List.of();
            }
        });

        assertThatThrownBy(() -> controller.list(99L, null, null, 1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("steps: 透传 runId")
    void steps_delegatesRunId() {
        AtomicLong seen = new AtomicLong();
        AgentStepVO step = new AgentStepVO();
        step.setId(1L);
        step.setSeq(1);

        TeacherAgentRunController controller = new TeacherAgentRunController(new TeacherAgentRunService() {
            @Override
            public AgentRunPageVO listRuns(Long courseId, Long studentId, int p, int s) {
                return null;
            }

            @Override
            public List<AgentStepVO> listSteps(Long runId) {
                seen.set(runId);
                return List.of(step);
            }
        });

        Result<List<AgentStepVO>> res = controller.steps(42L);
        assertThat(seen.get()).isEqualTo(42L);
        assertThat(res.getData()).hasSize(1);
    }

    @Test
    @DisplayName("steps: foreign run → 404")
    void steps_foreignRun404() {
        TeacherAgentRunController controller = new TeacherAgentRunController(new TeacherAgentRunService() {
            @Override
            public AgentRunPageVO listRuns(Long courseId, Long studentId, int p, int s) {
                return null;
            }

            @Override
            public List<AgentStepVO> listSteps(Long runId) {
                throw new BusinessException(ResultCode.NOT_FOUND, "轨迹不存在");
            }
        });

        assertThatThrownBy(() -> controller.steps(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("toggle off → 404")
    void toggleOff404() {
        TeacherAgentRunController controller = new TeacherAgentRunController(new TeacherAgentRunService() {
            @Override
            public AgentRunPageVO listRuns(Long courseId, Long studentId, int p, int s) {
                throw new BusinessException(ResultCode.NOT_FOUND, "轨迹功能未开放");
            }

            @Override
            public List<AgentStepVO> listSteps(Long runId) {
                throw new BusinessException(ResultCode.NOT_FOUND, "轨迹功能未开放");
            }
        });

        assertThatThrownBy(() -> controller.list(1L, null, null, 1, 20))
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
        assertThatThrownBy(() -> controller.steps(1L))
                .extracting("code")
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("list: kind 透传给服务（方向 7）")
    void list_passesKind() {
        AtomicReference<String> kind = new AtomicReference<>("unset");
        TeacherAgentRunController controller = new TeacherAgentRunController(new TeacherAgentRunService() {
            @Override
            public AgentRunPageVO listRuns(Long courseId, Long studentId, int p, int s) {
                throw new AssertionError("5-arg overload expected");
            }

            @Override
            public AgentRunPageVO listRuns(Long courseId, Long studentId, String k, int p, int s) {
                kind.set(k);
                return new AgentRunPageVO();
            }

            @Override
            public List<AgentStepVO> listSteps(Long runId) {
                return List.of();
            }
        });
        controller.list(1L, null, "TEACHER_CHAT", 1, 20);
        assertThat(kind.get()).isEqualTo("TEACHER_CHAT");
    }
}
