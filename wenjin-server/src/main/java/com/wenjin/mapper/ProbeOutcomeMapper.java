package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.ProbeOutcomeRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 探针最近结果 Mapper（V26）。 */
@Mapper
public interface ProbeOutcomeMapper extends BaseMapper<ProbeOutcomeRow> {

    /** 以 (student, course, node) 唯一键整行写入（后写覆盖）。 */
    @Insert("""
            INSERT INTO probe_outcome (student_id, course_id, node_code, correct, strategy, active_strategy,
                                       tried_strategies, switch_consumed, outcome_recorded, updated_at)
            VALUES (#{r.studentId}, #{r.courseId}, #{r.nodeCode}, #{r.correct}, #{r.strategy}, #{r.activeStrategy},
                    #{r.triedStrategies}, #{r.switchConsumed}, #{r.outcomeRecorded}, #{r.updatedAt})
            ON DUPLICATE KEY UPDATE correct = VALUES(correct), strategy = VALUES(strategy),
                active_strategy = VALUES(active_strategy), tried_strategies = VALUES(tried_strategies),
                switch_consumed = VALUES(switch_consumed), outcome_recorded = VALUES(outcome_recorded),
                updated_at = VALUES(updated_at)
            """)
    int upsert(@Param("r") ProbeOutcomeRow row);

    @Delete("DELETE FROM probe_outcome WHERE updated_at < #{cutoff}")
    int deleteUpdatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
