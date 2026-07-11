package com.wenjin.mapper;

import com.wenjin.entity.StudentMastery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 教师学情看板读模型：以选课 roster 为口径的聚合查询。
 */
@Mapper
public interface TeacherDashboardMapper {

    /** 选课表登记的学生数（roster）。 */
    int countEnrolled(@Param("courseId") Long courseId);

    /**
     * 已完成固定卷或自适应诊断的选课学生去重人数。
     * 仅计 roster 内学生；ghost 诊断会话不计。
     */
    int countDiagnosed(@Param("courseId") Long courseId);

    /**
     * 选课学生 × 生效知识点上的掌握行（排除未选课 ghost mastery、已归档节点）。
     * <p>
     * 以 {@code student_course} 为驱动表 LEFT JOIN 生效节点与 mastery，
     * 仅返回实际存在 mastery 行的组合（便于 Java 侧做读时衰减聚合）。
     */
    List<StudentMastery> selectEnrolledMastery(@Param("courseId") Long courseId);
}
