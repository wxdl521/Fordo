package com.wenjin.service.impl;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AdminDataResetVO;
import com.wenjin.entity.Course;
import com.wenjin.mapper.CourseAccessMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.LearningDataResetMapper;
import com.wenjin.service.AdminDataResetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按课程清空学生学习数据。
 *
 * <h2>清单（核对 schema.sql 全部同时带 student_id + course_id 的表及其子表，V18）</h2>
 * <p>按删除顺序（子表先于外键父表）：</p>
 * <ol>
 *   <li>{@code answer_record} — fk_answer_fixed_diag → diagnostic_session，须先于 diagnostic_session</li>
 *   <li>{@code diagnostic_session_question} — fk_dsq_session → diagnostic_session（经父表 JOIN 定位课程）</li>
 *   <li>{@code diagnostic_session}</li>
 *   <li>{@code adaptive_diag_session}（answer_record.session_id 只是逻辑引用）</li>
 *   <li>{@code practice_session} — fk_practice_path_item → learning_path_item，须先于路径明细</li>
 *   <li>{@code student_course.current_path_id} 置空（UPDATE，不删选课）— fk_sc_current_path → learning_path</li>
 *   <li>{@code learning_path_item} — fk_path_item_path → learning_path（经父表 JOIN）</li>
 *   <li>{@code learning_path}</li>
 *   <li>{@code student_mastery}</li>
 *   <li>{@code mastery_snapshot}</li>
 *   <li>{@code companion_message} — fk_companion_msg_conv → companion_conversation（经父表 JOIN）</li>
 *   <li>{@code companion_conversation}</li>
 *   <li>{@code student_memory}（课程维度长期记忆，逻辑外键）</li>
 *   <li>{@code agent_plan_task} — 逻辑引用 agent_plan（经父表 JOIN）</li>
 *   <li>{@code agent_plan}（学生学习计划）</li>
 *   <li>{@code teacher_message} — 逻辑引用 teacher_conversation（经父表 JOIN）</li>
 *   <li>{@code teacher_conversation}（教师 AI 助教会话，内容含学生姓名与学情）</li>
 * </ol>
 * <p><b>教师助教留痕的处理决策</b>：{@code agent_run} 的 TEACHER_CHAT 行按本注释下面的原则保留
 * （用量/监控），但其步骤 {@code agent_step.input_json/output_json}（含学生姓名、学情）与
 * {@code agent_run.input_digest} 置空（UPDATE，不计入 deleted）。</p>
 * <p><b>保留</b>：student_course（只断开 current_path_id）、图谱（kg_node / kg_edge）、题库（question*）、用户；
 * 以及运行留痕 agent_run / agent_step（监控与教师端轨迹、用量统计的来源，不是学习状态；
 * 其 conversation_id 为逻辑引用，会话删后成悬空 id，不影响读取）、教师侧 agent_report、ai_job、
 * 审计 / 登录日志、工具幂等键 tool_effect_key。</p>
 *
 * <h2>保护</h2>
 * <ul>
 *   <li>{@code prod} profile 下一律 403，除非显式 {@code wenjin.admin.data-reset-enabled=true}；</li>
 *   <li>{@code confirmText} 必须等于课程名（首尾空白忽略）；</li>
 *   <li>单事务，先对课程行加写锁（含已归档课程），与其它课程级写操作串行。</li>
 * </ul>
 */
@Service
public class AdminDataResetServiceImpl implements AdminDataResetService {

    private static final Logger log = LoggerFactory.getLogger(AdminDataResetServiceImpl.class);

    private final LearningDataResetMapper resetMapper;
    private final CourseMapper courseMapper;
    private final CourseAccessMapper courseAccessMapper;
    private final Environment environment;
    private final boolean enabledInProd;

    public AdminDataResetServiceImpl(LearningDataResetMapper resetMapper, CourseMapper courseMapper,
                                     CourseAccessMapper courseAccessMapper, Environment environment,
                                     @Value("${wenjin.admin.data-reset-enabled:false}") boolean enabledInProd) {
        this.resetMapper = resetMapper;
        this.courseMapper = courseMapper;
        this.courseAccessMapper = courseAccessMapper;
        this.environment = environment;
        this.enabledInProd = enabledInProd;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminDataResetVO resetCourse(Long courseId, String confirmText) {
        if (environment.acceptsProfiles(Profiles.of("prod")) && !enabledInProd) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "生产环境已禁用学习数据重置（如确需执行，请设置 wenjin.admin.data-reset-enabled=true 后重启）");
        }
        Course course = courseId == null ? null : courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }
        String name = course.getName() == null ? "" : course.getName().trim();
        if (confirmText == null || name.isEmpty() || !name.equals(confirmText.trim())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "确认文本与课程名不一致，未执行重置");
        }
        if (courseAccessMapper.lockCourseIncludingArchived(courseId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }

        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("answer_record", resetMapper.deleteAnswerRecords(courseId));
        deleted.put("diagnostic_session_question", resetMapper.deleteDiagnosticSessionQuestions(courseId));
        deleted.put("diagnostic_session", resetMapper.deleteDiagnosticSessions(courseId));
        deleted.put("adaptive_diag_session", resetMapper.deleteAdaptiveDiagSessions(courseId));
        deleted.put("practice_session", resetMapper.deletePracticeSessions(courseId));
        int currentPathCleared = resetMapper.clearCurrentPaths(courseId);
        deleted.put("learning_path_item", resetMapper.deleteLearningPathItems(courseId));
        deleted.put("learning_path", resetMapper.deleteLearningPaths(courseId));
        deleted.put("student_mastery", resetMapper.deleteStudentMastery(courseId));
        deleted.put("mastery_snapshot", resetMapper.deleteMasterySnapshots(courseId));
        deleted.put("companion_message", resetMapper.deleteCompanionMessages(courseId));
        deleted.put("companion_conversation", resetMapper.deleteCompanionConversations(courseId));
        deleted.put("student_memory", resetMapper.deleteStudentMemory(courseId));
        deleted.put("agent_plan_task", resetMapper.deleteAgentPlanTasks(courseId));
        deleted.put("agent_plan", resetMapper.deleteAgentPlans(courseId));
        deleted.put("teacher_message", resetMapper.deleteTeacherMessages(courseId));
        deleted.put("teacher_conversation", resetMapper.deleteTeacherConversations(courseId));
        int teacherTraceCleared = resetMapper.clearTeacherChatStepIo(courseId)
                + resetMapper.clearTeacherChatRunDigest(courseId);

        log.info("学习数据已重置 courseId={} deleted={} currentPathCleared={} teacherTraceCleared={}",
                courseId, deleted, currentPathCleared, teacherTraceCleared);
        return new AdminDataResetVO(deleted, currentPathCleared);
    }
}
