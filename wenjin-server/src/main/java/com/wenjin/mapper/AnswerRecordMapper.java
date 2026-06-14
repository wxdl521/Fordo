package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.AnswerRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 答题记录表 Mapper。
 */
@Mapper
public interface AnswerRecordMapper extends BaseMapper<AnswerRecord> {
}
