package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.StudentCourse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
