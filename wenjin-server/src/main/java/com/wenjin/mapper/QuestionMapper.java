package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.Question;
import org.apache.ibatis.annotations.Mapper;

/**
 * 题目表 Mapper。
 */
@Mapper
public interface QuestionMapper extends BaseMapper<Question> {
}
