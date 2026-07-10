package com.wenjin.service;

import com.wenjin.dto.TeacherCourseVO;

import java.util.List;

/** 老师端课程管理：列出 / 新增 / 删除（Task 7 前有关联数据则拒绝删除）。 */
public interface TeacherCourseService {
    List<TeacherCourseVO> list();

    /** 以当前主体为 owner 创建课程（忽略外部 teacherId）。 */
    TeacherCourseVO create(String name);

    void delete(Long courseId);

    /** 发布(true)/下架(false)课程，落到 course.status 1/0。 */
    void setPublished(Long courseId, boolean published);
}
