package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.dto.RunStepCount;
import com.wenjin.entity.AgentStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** agent_step 表访问。 */
@Mapper
public interface AgentStepMapper extends BaseMapper<AgentStep> {

    /**
     * 批量统计各 run 的 step 条数。
     * {@code runIds} 为空时不应调用（调用方短路）。
     */
    @Select("""
            <script>
            SELECT run_id AS runId, COUNT(*) AS stepCount
            FROM agent_step
            WHERE run_id IN
            <foreach collection="runIds" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            GROUP BY run_id
            </script>
            """)
    List<RunStepCount> countStepsByRunIds(@Param("runIds") Collection<Long> runIds);
}
