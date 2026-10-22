package com.wenjin.mapper;

import com.wenjin.entity.StudentMastery;
import com.wenjin.service.teacher.TeacherAnalyticsRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 教师助教学情读模型：全部以 {@code student_course} 为驱动表（只含选课学生），每个方法一条聚合 SQL。
 */
@Mapper
public interface TeacherAnalyticsMapper {

    /** 选课花名册（姓名 real_name 可空，由 Java 侧回退为脱敏用户名）。 */
    List<TeacherAnalyticsRows.RosterRow> selectRoster(@Param("courseId") Long courseId);

    /** 每名选课学生的作答汇总：最近作答时间、近 7 天作答/错题数、近 14 天作答/正确数。 */
    List<TeacherAnalyticsRows.StudentActivityRow> selectStudentActivity(@Param("courseId") Long courseId,
                                                                        @Param("since7") LocalDateTime since7,
                                                                        @Param("since14") LocalDateTime since14);

    /**
     * 每个 (学生, 节点) 在 {@code cutoff} 当时或之前的最近一条掌握快照；
     * {@code updated_at} 列承载 snapshot_at（便于复用读时衰减计算）。
     */
    List<StudentMastery> selectSnapshotsAtOrBefore(@Param("courseId") Long courseId,
                                                   @Param("cutoff") LocalDateTime cutoff);

    /** 按日作答量与活跃学生数。 */
    List<TeacherAnalyticsRows.DailyAnswerRow> selectDailyAnswers(@Param("courseId") Long courseId,
                                                                 @Param("since") LocalDateTime since);

    /** 按日「有学生发言的伴侣会话」数（只计数，不读消息内容）。 */
    List<TeacherAnalyticsRows.DailyCompanionRow> selectDailyCompanion(@Param("courseId") Long courseId,
                                                                      @Param("since") LocalDateTime since);

    /**
     * 近期客观题（单选/多选/判断）错误率最高的题；样本量不足 {@code minSample} 的题不返回。
     *
     * @param nodeCode 可空；按题目主节点过滤
     */
    List<TeacherAnalyticsRows.QuestionErrorRow> selectQuestionErrorStats(@Param("courseId") Long courseId,
                                                                         @Param("since") LocalDateTime since,
                                                                         @Param("nodeCode") String nodeCode,
                                                                         @Param("minSample") int minSample,
                                                                         @Param("limit") int limit);

    /** 指定题目在窗口内的作答选项分布（按 question_id × student_answer 聚合）。 */
    List<TeacherAnalyticsRows.AnswerChoiceRow> selectAnswerChoices(@Param("courseId") Long courseId,
                                                                   @Param("since") LocalDateTime since,
                                                                   @Param("questionIds") Collection<Long> questionIds);
}
