package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.AdaptiveDiagSession;
import org.apache.ibatis.annotations.Mapper;

/** 自适应诊断会话表 Mapper（M2 图引导自适应诊断）。 */
@Mapper
public interface AdaptiveDiagSessionMapper extends BaseMapper<AdaptiveDiagSession> {
}
