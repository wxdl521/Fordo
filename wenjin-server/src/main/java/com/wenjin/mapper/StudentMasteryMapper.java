package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.StudentMastery;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 学生掌握度表 Mapper。
 * <p>并发更新走 {@link #insertEnsure} + {@link #selectForUpdate}，按 nodeId 升序加锁防死锁。
 */
@Mapper
public interface StudentMasteryMapper extends BaseMapper<StudentMastery> {

    /**
     * 确保掌握度行存在（幂等）：已存在则 no-op（{@code id=id}），新建默认分 0。
     *
     * @return MySQL 影响行数：1=新插入，0=已存在无变化
     */
    @Insert("""
            INSERT INTO student_mastery (student_id, course_id, node_id, mastery_score, mastery_level)
            VALUES (#{studentId}, #{courseId}, #{nodeId}, 0, 0)
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertEnsure(@Param("studentId") Long studentId,
                     @Param("courseId") Long courseId,
                     @Param("nodeId") Long nodeId);

    /**
     * 锁定读（SELECT … FOR UPDATE），用于并发安全的掌握度更新。
     */
    @Select("""
            SELECT * FROM student_mastery
            WHERE student_id=#{studentId} AND course_id=#{courseId} AND node_id=#{nodeId}
            FOR UPDATE
            """)
    StudentMastery selectForUpdate(@Param("studentId") Long studentId,
                                   @Param("courseId") Long courseId,
                                   @Param("nodeId") Long nodeId);
}
