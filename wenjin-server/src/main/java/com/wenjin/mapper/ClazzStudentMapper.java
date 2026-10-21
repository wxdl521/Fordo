package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.ClazzStudent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;

/**
 * 班级成员表 Mapper。student_id 全表唯一，所以"转班"是更新 clazz_id，而不是再插一行。
 */
@Mapper
public interface ClazzStudentMapper extends BaseMapper<ClazzStudent> {

    /** 一条 SQL 批量插入成员（调用方保证这些学生当前不在任何班级）。 */
    @Insert("""
            <script>
            INSERT INTO clazz_student (clazz_id, student_id) VALUES
            <foreach collection="studentIds" item="sid" separator=",">(#{clazzId}, #{sid})</foreach>
            </script>
            """)
    int insertBatch(@Param("clazzId") Long clazzId, @Param("studentIds") Collection<Long> studentIds);

    /** 一条 SQL 把这些学生转入目标班级（调用方保证他们当前在别的班级）。 */
    @Update("""
            <script>
            UPDATE clazz_student SET clazz_id = #{clazzId}
            WHERE student_id IN
            <foreach collection="studentIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int moveToClazz(@Param("clazzId") Long clazzId, @Param("studentIds") Collection<Long> studentIds);
}
