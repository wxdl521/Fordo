package com.wenjin.service;

import com.wenjin.dto.CourseWithMasteryVO;
import com.wenjin.entity.Course;

import java.util.List;

/**
 * 选课服务。
 */
public interface CourseService {

    /**
     * 学生选课。
     *
     * @param studentId 学生 ID
     * @param courseId  课程 ID
     */
    void enroll(Long studentId, Long courseId);

    /**
     * 查询学生已选课程列表，附带掌握度统计。
     *
     * @param studentId 学生 ID
     * @return 已选课程 + 掌握度统计
     */
    List<CourseWithMasteryVO> getMyCourses(Long studentId);

    /**
     * 查询所有可用课程（status=1）。
     *
     * @return 可用课程列表
     */
    List<Course> getAvailableCourses();

}
