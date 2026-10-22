package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** AI 测试连接：{@code {target: "text" | "vision" | "embedding"}}。 */
@Data
public class AdminAiTestRequest {

    @NotBlank(message = "target 不能为空")
    @Pattern(regexp = "text|vision|embedding", message = "target 须为 text、vision 或 embedding")
    private String target;
}
