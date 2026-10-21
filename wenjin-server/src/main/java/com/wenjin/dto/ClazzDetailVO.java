package com.wenjin.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** 班级详情：{@link ClazzVO} 全部字段 + 成员 + 关联课程。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ClazzDetailVO extends ClazzVO {

    private List<ClazzStudentVO> students = new ArrayList<>();
    private List<ClazzCourseVO> courses = new ArrayList<>();
}
