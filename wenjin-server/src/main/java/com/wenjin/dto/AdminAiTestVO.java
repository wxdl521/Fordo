package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** AI 测试连接结果。{@code error} 为脱敏后的摘要（不含 API Key），成功时不出现。 */
@Data
public class AdminAiTestVO {

    private boolean ok;
    private long latencyMs;
    private String model;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;

    public AdminAiTestVO() {}

    public AdminAiTestVO(boolean ok, long latencyMs, String model, String error) {
        this.ok = ok;
        this.latencyMs = latencyMs;
        this.model = model;
        this.error = error;
    }
}
