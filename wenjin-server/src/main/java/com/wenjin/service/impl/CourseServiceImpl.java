package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.CourseWithMasteryVO;
import com.wenjin.entity.Course;
import com.wenjin.entity.StudentCourse;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.StudentCourseMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.CourseService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 选课服务实现。
 */
@Service
public class CourseServiceImpl implements CourseService {

    private final StudentCourseMapper studentCourseMapper;
    private final CourseMapper courseMapper;
    private final StudentMasteryMapper studentMasteryMapper;

    public CourseServiceImpl(StudentCourseMapper studentCourseMapper,
                             CourseMapper courseMapper,
                             StudentMasteryMapper studentMasteryMapper) {
        this.studentCourseMapper = studentCourseMapper;
        this.courseMapper = courseMapper;
        this.studentMasteryMapper = studentMasteryMapper;
    }

    @Override
    public void enroll(Long studentId, Long courseId) {
        // 校验课程存在
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }
        if (course.getStatus() != null && course.getStatus() == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "课程已停用");
        }

        // 检查是否已选
        Long count = studentCourseMapper.selectCount(
                new LambdaQueryWrapper<StudentCourse>()
                        .eq(StudentCourse::getStudentId, studentId)
                        .eq(StudentCourse::getCourseId, courseId));
        if (count > 0) {
            return; // 已选，幂等
        }

        StudentCourse sc = new StudentCourse();
        sc.setStudentId(studentId);
        sc.setCourseId(courseId);
        studentCourseMapper.insert(sc);
    }

    @Override
    public List<CourseWithMasteryVO> getMyCourses(Long studentId) {
        // 1. 查该学生所有选课记录
        List<StudentCourse> enrollments = studentCourseMapper.selectList(
                new LambdaQueryWrapper<StudentCourse>()
                        .eq(StudentCourse::getStudentId, studentId));
        if (enrollments.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> courseIds = enrollments.stream()
                .map(StudentCourse::getCourseId)
                .collect(Collectors.toList());

        // 2. 批量查课程信息
        List<Course> courses = courseMapper.selectBatchIds(courseIds);
        Map<Long, Course> courseMap = courses.stream()
                .collect(Collectors.toMap(Course::getId, c -> c));

        // 3. 批量查该学生所有掌握度记录
        List<StudentMastery> masteries = studentMasteryMapper.selectList(
                new LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .in(StudentMastery::getCourseId, courseIds));

        // 按 courseId 分组，再按 mastery_level 统计
        Map<Long, Map<Integer, Long>> masteryByCourse = masteries.stream()
                .collect(Collectors.groupingBy(
                        StudentMastery::getCourseId,
                        Collectors.groupingBy(StudentMastery::getMasteryLevel, Collectors.counting())));

        // 4. 组装 VO
        return courseIds.stream()
                .map(courseId -> {
                    Course c = courseMap.get(courseId);
                    if (c == null) return null;

                    CourseWithMasteryVO vo = new CourseWithMasteryVO();
                    vo.setCourseId(c.getId());
                    vo.setCode(c.getCode());
                    vo.setName(c.getName());
                    vo.setDescription(c.getDescription());

                    Map<Integer, Long> levelMap = masteryByCourse.getOrDefault(courseId, Collections.emptyMap());
                    vo.setMasteredCount(levelMap.getOrDefault(2, 0L));
                    vo.setWeakCount(levelMap.getOrDefault(1, 0L));
                    vo.setUnlearnedCount(levelMap.getOrDefault(0, 0L));

                    return vo;
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<Course> getAvailableCourses() {
        return courseMapper.selectList(
                new LambdaQueryWrapper<Course>()
                        .eq(Course::getStatus, 1));
    }

    @Override
    public void autoEnrollAll(Long studentId) {
        List<Course> available = getAvailableCourses();
        if (available.isEmpty()) {
            return;
        }

        // 查已选课程 ID
        Set<Long> enrolledIds = studentCourseMapper.selectList(
                new LambdaQueryWrapper<StudentCourse>()
                        .eq(StudentCourse::getStudentId, studentId))
                .stream()
                .map(StudentCourse::getCourseId)
                .collect(Collectors.toSet());

        // 批量插入未选课程
        for (Course course : available) {
            if (!enrolledIds.contains(course.getId())) {
                StudentCourse sc = new StudentCourse();
                sc.setStudentId(studentId);
                sc.setCourseId(course.getId());
                studentCourseMapper.insert(sc);
            }
        }
    }
}
