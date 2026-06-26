package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.TeacherCourseVO;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.service.TeacherCourseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TeacherCourseServiceImpl implements TeacherCourseService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CourseMapper courseMapper;
    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;

    @Value("${wenjin.demo.teacher-id:1}")
    private Long demoTeacherId;

    public TeacherCourseServiceImpl(CourseMapper courseMapper, KgNodeMapper nodeMapper,
                                    KgEdgeMapper edgeMapper) {
        this.courseMapper = courseMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
    }

    @Override
    public List<TeacherCourseVO> list() {
        return courseMapper.selectList(
                        new LambdaQueryWrapper<Course>()
                                .eq(Course::getStatus, 1)
                                .orderByAsc(Course::getId))
                .stream()
                .map(c -> new TeacherCourseVO(c.getId(), c.getCode(), c.getName()))
                .collect(Collectors.toList());
    }

    @Override
    public TeacherCourseVO create(String name, Long teacherId) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "课程名不能为空");
        }
        Course c = new Course();
        c.setCode(generateUniqueCode());
        c.setName(name.trim());
        c.setTeacherId(teacherId != null ? teacherId : demoTeacherId);
        c.setStatus(1);
        courseMapper.insert(c);
        return new TeacherCourseVO(c.getId(), c.getCode(), c.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long courseId) {
        Course c = courseMapper.selectById(courseId);
        if (c == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在：" + courseId);
        }
        edgeMapper.delete(new LambdaQueryWrapper<KgEdge>().eq(KgEdge::getCourseId, courseId));
        nodeMapper.delete(new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));
        courseMapper.deleteById(courseId);
    }

    private String generateUniqueCode() {
        for (int i = 0; i < 20; i++) {
            String code = randomCode();
            Long n = courseMapper.selectCount(
                    new LambdaQueryWrapper<Course>().eq(Course::getCode, code));
            if (n == null || n == 0) {
                return code;
            }
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "课程编码生成失败，请重试");
    }

    static String randomCode() {
        byte[] b = new byte[5];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(10);
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }
}
