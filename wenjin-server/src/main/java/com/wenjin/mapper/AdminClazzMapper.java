package com.wenjin.mapper;

import com.wenjin.dto.AdminRows;
import com.wenjin.dto.ClazzCourseVO;
import com.wenjin.dto.ClazzStudentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 管理后台班级列表 / 详情的只读查询（SQL 在 {@code mapper/AdminClazzMapper.xml}）。
 * 人数、课程数用 GROUP BY 子查询在同一条 SQL 内取齐。
 */
@Mapper
public interface AdminClazzMapper {

    /** 班级总数；keyword 为已转义的班级名关键字，null=不筛。 */
    long countClazzes(@Param("keyword") String keyword);

    /** 一页班级，按 id 倒序。 */
    List<AdminRows.ClazzRow> selectClazzPage(@Param("keyword") String keyword,
                                             @Param("offset") long offset,
                                             @Param("size") int size);

    /** 单个班级；不存在返回 null。 */
    AdminRows.ClazzRow selectClazzById(@Param("id") Long id);

    /** 班级成员（学生），按用户名排序。 */
    List<ClazzStudentVO> selectMembers(@Param("clazzId") Long clazzId);

    /** 班级关联的课程（含已归档），按关联先后排序。 */
    List<ClazzCourseVO> selectLinkedCourses(@Param("clazzId") Long clazzId);
}
