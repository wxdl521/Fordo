package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.StudentCourse;
import org.apache.ibatis.annotations.Mapper;

/**
 * 学生选课表 Mapper。
 */
@Mapper
public interface StudentCourseMapper extends BaseMapper<StudentCourse> {
}
