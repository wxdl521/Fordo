package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** audit_log 表访问。 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /** 库里出现过的 action 去重（供筛选下拉），走 idx_audit_action。 */
    @Select("SELECT DISTINCT action FROM audit_log ORDER BY action")
    List<String> selectDistinctActions();
}
