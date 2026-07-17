package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.DiagnosticSessionQuestion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 固定卷诊断会话冻结题目 Mapper。
 */
@Mapper
public interface DiagnosticSessionQuestionMapper extends BaseMapper<DiagnosticSessionQuestion> {
}
