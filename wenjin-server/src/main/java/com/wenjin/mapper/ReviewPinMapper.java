package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.ReviewPin;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 复习置顶 Mapper。
 */
@Mapper
public interface ReviewPinMapper extends BaseMapper<ReviewPin> {

    /**
     * 同日同节点幂等写入；mode 以最后一次为准。
     */
    @Insert("""
            INSERT INTO review_pin (student_id, course_id, pin_date, node_code, mode, created_at, updated_at)
            VALUES (#{studentId}, #{courseId}, #{pinDate}, #{nodeCode}, #{mode}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE mode = VALUES(mode), updated_at = NOW()
            """)
    int upsert(@Param("studentId") Long studentId,
               @Param("courseId") Long courseId,
               @Param("pinDate") java.time.LocalDate pinDate,
               @Param("nodeCode") String nodeCode,
               @Param("mode") String mode);
}
