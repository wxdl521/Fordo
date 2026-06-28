package com.wenjin.service;

import com.wenjin.dto.TeacherCourseVO;

import java.util.List;

/** 老师端课程管理：列出 / 新增 / 删除（删除连同其图谱节点与边）。 */
public interface TeacherCourseService {
    List<TeacherCourseVO> list();
    TeacherCourseVO create(String name, Long teacherId);
    void delete(Long courseId);

    /** 发布(true)/下架(false)课程，落到 course.status 1/0。 */
    void setPublished(Long courseId, boolean published);
}
