package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.QuestionNode;
import org.apache.ibatis.annotations.Mapper;

/**
 * 题目-知识点关联表 Mapper。
 */
@Mapper
public interface QuestionNodeMapper extends BaseMapper<QuestionNode> {
}
