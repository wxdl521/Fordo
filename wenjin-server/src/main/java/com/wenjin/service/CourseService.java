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

    /**
     * 学生侧数据访问守卫：课程必须存在且已发布（status=1）；studentId 非空时还须已选该课程。
     * 阻止用缓存/书签的 courseId 越过课程列表，访问已下架或未选课程的 /map、诊断、路径、成长等数据。
     *
     * @param studentId 学生 ID（可空：仅校验发布状态，不校验选课）
     * @param courseId  课程 ID
     */
    void assertAccessibleByStudent(Long studentId, Long courseId);

}
