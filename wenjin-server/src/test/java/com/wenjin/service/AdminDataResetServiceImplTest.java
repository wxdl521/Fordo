package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AdminDataResetVO;
import com.wenjin.entity.Course;
import com.wenjin.mapper.CourseAccessMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.LearningDataResetMapper;
import com.wenjin.service.impl.AdminDataResetServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AdminDataResetServiceImpl：prod 保护、确认文本、删表清单与顺序（子表先删）、单事务。
 */
@ExtendWith(MockitoExtension.class)
class AdminDataResetServiceImplTest {

    private static final long COURSE_ID = 5L;

    @Mock
    private LearningDataResetMapper resetMapper;
    @Mock
    private CourseMapper courseMapper;
    @Mock
    private CourseAccessMapper courseAccessMapper;

    private MockEnvironment env;

    @BeforeEach
    void setUp() {
        env = new MockEnvironment();
        env.setActiveProfiles("dev");
    }

    private AdminDataResetServiceImpl service(boolean enabledInProd) {
        return new AdminDataResetServiceImpl(resetMapper, courseMapper, courseAccessMapper, env, enabledInProd);
    }

    @Test
    @DisplayName("按 子表→父表 顺序清空 16 张表（另清空教师助教留痕的步骤 I/O） + 断开选课的 current_path_id；返回每表删除行数（顺序即删除顺序）")
    void reset_deletesInDependencyOrder() {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(course("软件工程"));
        when(courseAccessMapper.lockCourseIncludingArchived(COURSE_ID)).thenReturn(COURSE_ID);
        when(resetMapper.deleteAnswerRecords(COURSE_ID)).thenReturn(120);
        when(resetMapper.deleteDiagnosticSessionQuestions(COURSE_ID)).thenReturn(50);
        when(resetMapper.deleteDiagnosticSessions(COURSE_ID)).thenReturn(2);
        when(resetMapper.deleteAdaptiveDiagSessions(COURSE_ID)).thenReturn(3);
        when(resetMapper.deletePracticeSessions(COURSE_ID)).thenReturn(4);
        when(resetMapper.clearCurrentPaths(COURSE_ID)).thenReturn(6);
        when(resetMapper.deleteLearningPathItems(COURSE_ID)).thenReturn(30);
        when(resetMapper.deleteLearningPaths(COURSE_ID)).thenReturn(7);
        when(resetMapper.deleteStudentMastery(COURSE_ID)).thenReturn(39);
        when(resetMapper.deleteMasterySnapshots(COURSE_ID)).thenReturn(80);
        when(resetMapper.deleteCompanionMessages(COURSE_ID)).thenReturn(40);
        when(resetMapper.deleteCompanionConversations(COURSE_ID)).thenReturn(5);
        when(resetMapper.deleteStudentMemory(COURSE_ID)).thenReturn(8);
        when(resetMapper.deleteAgentPlanTasks(COURSE_ID)).thenReturn(9);
        when(resetMapper.deleteAgentPlans(COURSE_ID)).thenReturn(1);
        when(resetMapper.deleteTeacherMessages(COURSE_ID)).thenReturn(11);
        when(resetMapper.deleteTeacherConversations(COURSE_ID)).thenReturn(2);

        AdminDataResetVO vo = service(false).resetCourse(COURSE_ID, " 软件工程 ");

        InOrder order = inOrder(courseAccessMapper, resetMapper);
        order.verify(courseAccessMapper).lockCourseIncludingArchived(COURSE_ID);
        order.verify(resetMapper).deleteAnswerRecords(COURSE_ID);
        order.verify(resetMapper).deleteDiagnosticSessionQuestions(COURSE_ID);
        order.verify(resetMapper).deleteDiagnosticSessions(COURSE_ID);
        order.verify(resetMapper).deleteAdaptiveDiagSessions(COURSE_ID);
        order.verify(resetMapper).deletePracticeSessions(COURSE_ID);
        order.verify(resetMapper).clearCurrentPaths(COURSE_ID);
        order.verify(resetMapper).deleteLearningPathItems(COURSE_ID);
        order.verify(resetMapper).deleteLearningPaths(COURSE_ID);
        order.verify(resetMapper).deleteStudentMastery(COURSE_ID);
        order.verify(resetMapper).deleteMasterySnapshots(COURSE_ID);
        order.verify(resetMapper).deleteCompanionMessages(COURSE_ID);
        order.verify(resetMapper).deleteCompanionConversations(COURSE_ID);
        order.verify(resetMapper).deleteStudentMemory(COURSE_ID);
        order.verify(resetMapper).deleteAgentPlanTasks(COURSE_ID);
        order.verify(resetMapper).deleteAgentPlans(COURSE_ID);
        order.verify(resetMapper).deleteTeacherMessages(COURSE_ID);
        order.verify(resetMapper).deleteTeacherConversations(COURSE_ID);
        order.verify(resetMapper).clearTeacherChatStepIo(COURSE_ID);
        order.verify(resetMapper).clearTeacherChatRunDigest(COURSE_ID);
        order.verifyNoMoreInteractions();

        assertThat(vo.getDeleted()).containsExactly(
                entry("answer_record", 120),
                entry("diagnostic_session_question", 50),
                entry("diagnostic_session", 2),
                entry("adaptive_diag_session", 3),
                entry("practice_session", 4),
                entry("learning_path_item", 30),
                entry("learning_path", 7),
                entry("student_mastery", 39),
                entry("mastery_snapshot", 80),
                entry("companion_message", 40),
                entry("companion_conversation", 5),
                entry("student_memory", 8),
                entry("agent_plan_task", 9),
                entry("agent_plan", 1),
                entry("teacher_message", 11),
                entry("teacher_conversation", 2));
        assertThat(vo.getDeleted()).doesNotContainKeys("student_course", "kg_node", "question", "sys_user");
        assertThat(vo.getCurrentPathCleared()).isEqualTo(6);
    }

    @Test
    @DisplayName("确认文本与课程名不一致 → 400，不加锁、不删任何数据")
    void reset_confirmMismatch_400() {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(course("软件工程"));

        for (String wrong : new String[]{"软件", "软件工程2", "", null}) {
            assertThatThrownBy(() -> service(false).resetCourse(COURSE_ID, wrong))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo(ResultCode.BAD_REQUEST.getCode()));
        }
        verifyNoInteractions(resetMapper, courseAccessMapper);
    }

    @Test
    @DisplayName("课程不存在 → 404")
    void reset_courseNotFound_404() {
        assertThatThrownBy(() -> service(false).resetCourse(99L, "随便"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode()));
        verifyNoInteractions(resetMapper);
    }

    @Test
    @DisplayName("prod profile 默认 403（先于任何查询）；显式 data-reset-enabled=true 才放行")
    void reset_prodGuard() {
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> service(false).resetCourse(COURSE_ID, "软件工程"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode());
                    assertThat(e.getMessage()).contains("生产环境");
                });
        verifyNoInteractions(courseMapper, courseAccessMapper, resetMapper);

        when(courseMapper.selectById(COURSE_ID)).thenReturn(course("软件工程"));
        when(courseAccessMapper.lockCourseIncludingArchived(COURSE_ID)).thenReturn(COURSE_ID);
        AdminDataResetVO vo = service(true).resetCourse(COURSE_ID, "软件工程");
        assertThat(vo.getDeleted()).hasSize(16);
    }

    @Test
    @DisplayName("单事务：方法标 @Transactional(rollbackFor = Exception.class)")
    void reset_isTransactional() throws Exception {
        Transactional tx = AdminDataResetServiceImpl.class
                .getMethod("resetCourse", Long.class, String.class).getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.rollbackFor()).contains(Exception.class);
    }

    private static Course course(String name) {
        Course c = new Course();
        c.setId(COURSE_ID);
        c.setName(name);
        return c;
    }

    private static java.util.Map.Entry<String, Integer> entry(String k, int v) {
        return java.util.Map.entry(k, v);
    }
}
