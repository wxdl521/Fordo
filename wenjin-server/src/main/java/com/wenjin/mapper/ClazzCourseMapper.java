package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.ClazzCourse;
import org.apache.ibatis.annotations.Mapper;

/**
 * 班级关联课程表 Mapper。
 */
@Mapper
public interface ClazzCourseMapper extends BaseMapper<ClazzCourse> {
}
