package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.SysConfig;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** sys_config 表访问。 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {

    /**
     * 写入或覆盖一条配置（按主键 config_key upsert，多实例并发保存也不会撞唯一键）。
     * updated_at 显式刷新：值未变时 ON UPDATE 不会触发，但"谁、何时保存过"仍要更新。
     */
    @Insert("""
            INSERT INTO sys_config (config_key, config_value, encrypted, updated_by, updated_at)
            VALUES (#{key}, #{value}, #{encrypted}, #{updatedBy}, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE config_value = #{value}, encrypted = #{encrypted},
                updated_by = #{updatedBy}, updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(@Param("key") String key, @Param("value") String value,
               @Param("encrypted") int encrypted, @Param("updatedBy") Long updatedBy);
}
