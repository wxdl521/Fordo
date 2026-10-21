package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.LoginLog;
import org.apache.ibatis.annotations.Mapper;

/** login_log 表访问。 */
@Mapper
public interface LoginLogMapper extends BaseMapper<LoginLog> {
}
