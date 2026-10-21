package com.wenjin.mapper;

import com.wenjin.dto.AdminCourseQuery;
import com.wenjin.dto.AdminRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 管理后台课程列表的只读查询（SQL 在 {@code mapper/AdminCourseMapper.xml}）。
 * 任课教师、节点数、学生数、班级数全部在一条 SQL 内用 LEFT JOIN + GROUP BY 子查询取齐，不 N+1。
 */
@Mapper
public interface AdminCourseMapper {

    /** 满足筛选条件的课程总数（含归档课程，除非 archived 筛选另有指定）。 */
    long countCourses(@Param("q") AdminCourseQuery q);

    /** 一页课程，按 id 倒序。 */
    List<AdminRows.CourseRow> selectCoursePage(@Param("q") AdminCourseQuery q);

    /** 单个课程（含归档）；不存在返回 null。 */
    AdminRows.CourseRow selectCourseById(@Param("id") Long id);
}
