package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.QuestionOption;
import org.apache.ibatis.annotations.Mapper;

/**
 * 题目选项表 Mapper。
 */
@Mapper
public interface QuestionOptionMapper extends BaseMapper<QuestionOption> {
}
