package com.wenjin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 批量通过 pending 映射：可选节点与最低置信度过滤。
 */
@Data
public class BatchApproveMapsRequest {

    @Size(max = 32)
    private String nodeCode;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private BigDecimal minConfidence;
}
