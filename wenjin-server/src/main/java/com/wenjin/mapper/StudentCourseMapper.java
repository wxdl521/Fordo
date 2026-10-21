package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.StudentCourse;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;

/**
 * 学生选课表 Mapper。
 * <p>生成学习路径时对选课行 {@link #selectForUpdate}，串行化 current_path_id 切换。
 */
@Mapper
public interface StudentCourseMapper extends BaseMapper<StudentCourse> {

    /**
     * 锁定读（SELECT … FOR UPDATE），用于并发安全的路径切换。
     */
    @Select("""
            SELECT * FROM student_course
            WHERE student_id=#{studentId} AND course_id=#{courseId}
            FOR UPDATE
            """)
    StudentCourse selectForUpdate(@Param("studentId") Long studentId,
                                  @Param("courseId") Long courseId);

    /**
     * 班级关联课程：为班级内全部学生补选该课程（一条 INSERT IGNORE ... SELECT，已选的由
     * uk_student_course 忽略）。只补 role=学生 的成员。返回<b>新增</b>的 student_course 行数。
     */
    @Insert("""
            INSERT IGNORE INTO student_course (student_id, course_id)
            SELECT cs.student_id, #{courseId}
            FROM clazz_student cs
            JOIN sys_user u ON u.id = cs.student_id AND u.role = 2
            WHERE cs.clazz_id = #{clazzId}
            """)
    int enrollClazzMembersInCourse(@Param("clazzId") Long clazzId,
                                   @Param("courseId") Long courseId);

    /**
     * 学生加入班级：为这些学生补选该班级已关联的全部课程（一条 INSERT IGNORE ... SELECT，
     * 已选的忽略）。要求这些学生此刻已是该班成员。返回<b>新增</b>的 student_course 行数。
     */
    @Insert("""
            <script>
            INSERT IGNORE INTO student_course (student_id, course_id)
            SELECT cs.student_id, cc.course_id
            FROM clazz_student cs
            JOIN clazz_course cc ON cc.clazz_id = cs.clazz_id
            WHERE cs.clazz_id = #{clazzId}
              AND cs.student_id IN
            <foreach collection="studentIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int enrollStudentsInClazzCourses(@Param("clazzId") Long clazzId,
                                     @Param("studentIds") Collection<Long> studentIds);
}
