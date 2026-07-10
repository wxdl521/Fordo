package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.TeacherCourseVO;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.service.TeacherCourseService;
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
    private final QuestionMapper questionMapper;
    private final CourseAccessService courseAccessService;

    public TeacherCourseServiceImpl(CourseMapper courseMapper, KgNodeMapper nodeMapper,
                                    KgEdgeMapper edgeMapper, QuestionMapper questionMapper,
                                    CourseAccessService courseAccessService) {
        this.courseMapper = courseMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.questionMapper = questionMapper;
        this.courseAccessService = courseAccessService;
    }

    @Override
    public List<TeacherCourseVO> list() {
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        LambdaQueryWrapper<Course> q = new LambdaQueryWrapper<Course>().orderByAsc(Course::getId);
        // 教师仅见自己的课；ADMIN 可列全部
        if (!principal.isAdmin()) {
            q.eq(Course::getTeacherId, principal.userId());
        }
        return courseMapper.selectList(q)
                .stream()
                .map(c -> new TeacherCourseVO(c.getId(), c.getCode(), c.getName(),
                        c.getStatus() != null && c.getStatus() == 1))
                .collect(Collectors.toList());
    }

    @Override
    public TeacherCourseVO create(String name) {
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "课程名不能为空");
        }
        Course c = new Course();
        c.setCode(generateUniqueCode());
        c.setName(name.trim());
        // 始终以当前主体为 owner，禁止客户端指定 teacherId
        c.setTeacherId(principal.userId());
        c.setStatus(0);
        courseMapper.insert(c);
        return new TeacherCourseVO(c.getId(), c.getCode(), c.getName(), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long courseId) {
        courseAccessService.assertManageCourse(courseId);
        // Task 7 前：拒绝删除仍有关联数据的课程（不再级联清空）
        Long nodeCnt = nodeMapper.selectCount(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));
        Long edgeCnt = edgeMapper.selectCount(
                new LambdaQueryWrapper<KgEdge>().eq(KgEdge::getCourseId, courseId));
        Long questionCnt = questionMapper.selectCount(
                new LambdaQueryWrapper<Question>().eq(Question::getCourseId, courseId));
        if ((nodeCnt != null && nodeCnt > 0)
                || (edgeCnt != null && edgeCnt > 0)
                || (questionCnt != null && questionCnt > 0)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "课程仍有关联数据，暂不可删除");
        }
        int deleted = courseMapper.deleteById(courseId);
        if (deleted == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
    }

    @Override
    public void setPublished(Long courseId, boolean published) {
        courseAccessService.assertManageCourse(courseId);
        Course c = courseMapper.selectById(courseId);
        if (c == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        c.setStatus(published ? 1 : 0);
        courseMapper.updateById(c);
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
