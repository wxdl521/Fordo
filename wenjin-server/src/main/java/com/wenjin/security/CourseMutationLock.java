package com.wenjin.security;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.mapper.CourseAccessMapper;
import org.springframework.stereotype.Component;

@Component
public class CourseMutationLock {

    private final CourseAccessMapper courseAccessMapper;

    public CourseMutationLock(CourseAccessMapper courseAccessMapper) {
        this.courseAccessMapper = courseAccessMapper;
    }

    public void lockActive(Long courseId) {
        if (courseAccessMapper.lockActiveCourse(courseId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
    }
}
