package com.wenjin.service;

import com.wenjin.dto.TeacherCourseVO;

import java.util.List;

/** 老师端课程管理：列出 / 新增 / 删除（删除连同其图谱节点与边）。 */
public interface TeacherCourseService {
    List<TeacherCourseVO> list();
    TeacherCourseVO create(String name, Long teacherId);
    void delete(Long courseId);
}
