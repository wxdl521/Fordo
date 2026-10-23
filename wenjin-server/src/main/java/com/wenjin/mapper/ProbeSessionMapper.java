package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.ProbeSessionRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** 理解探针会话 Mapper（V26）。 */
@Mapper
public interface ProbeSessionMapper extends BaseMapper<ProbeSessionRow> {

    /** 比较并设置：仅首次提交生效（submitted_at IS NULL），返回 1=本次抢到，0=已被提交过。 */
    @Update("""
            UPDATE probe_session SET submitted_at = #{at}, correct = #{correct}
            WHERE session_id = #{sessionId} AND submitted_at IS NULL
            """)
    int markSubmitted(@Param("sessionId") String sessionId,
                      @Param("at") LocalDateTime at,
                      @Param("correct") boolean correct);

    @Delete("DELETE FROM probe_session WHERE created_at < #{cutoff}")
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
