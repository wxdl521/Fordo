package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.Clazz;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 班级表 Mapper。
 */
@Mapper
public interface ClazzMapper extends BaseMapper<Clazz> {

    /**
     * 按主键锁定读（InnoDB 当前读）：班级的成员 / 课程变更与删除串行化，
     * 避免往一个正在被删除的班级里加人（外键冲突）。清缓存以保证等锁后读到最新行。
     */
    @Select("SELECT * FROM clazz WHERE id = #{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Clazz selectByIdForUpdate(@Param("id") Long id);
}
