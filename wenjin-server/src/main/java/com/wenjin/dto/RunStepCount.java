package com.wenjin.dto;

import lombok.Data;

/** Mapper 投影：run 的 step 条数。 */
@Data
public class RunStepCount {

    private Long runId;
    private Integer stepCount;
}
