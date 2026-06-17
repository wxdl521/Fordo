package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
