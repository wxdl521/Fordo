package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.entity.Course;
import com.wenjin.mapper.CourseMapper;

import java.security.SecureRandom;

/**
 * 课程业务编码（course.code）的生成规则：10 位大写十六进制，查重后返回。
 * 教师端建课与管理后台为指定教师建课共用同一处，避免两份规则漂移。
 */
final class CourseCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 20;

    private CourseCodes() {}

    /** 随机生成直到库里没有同码课程（含已归档课程，uk_code 对全表唯一）；连续撞码则抛 500。 */
    static String generateUnique(CourseMapper courseMapper) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            String code = random();
            Long n = courseMapper.selectCount(
                    new LambdaQueryWrapper<Course>().eq(Course::getCode, code));
            if (n == null || n == 0) {
                return code;
            }
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "课程编码生成失败，请重试");
    }

    /** 5 个随机字节 → 10 位大写十六进制，如 52015CC4B4。 */
    static String random() {
        byte[] b = new byte[5];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(10);
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }
}
