package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.DiagnosticSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 固定卷诊断会话 Mapper。
 */
@Mapper
public interface DiagnosticSessionMapper extends BaseMapper<DiagnosticSession> {

    /**
     * 按 token 锁定读（SELECT … FOR UPDATE），用于并发安全的交卷。
     */
    @Select("SELECT * FROM diagnostic_session WHERE token = #{token} FOR UPDATE")
    DiagnosticSession selectByTokenForUpdate(@Param("token") String token);
}
