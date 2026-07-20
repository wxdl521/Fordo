package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 教师端上传 NODE_REF Markdown 请求。
 */
@Data
public class NodeRefIngestRequest {

    @NotNull
    @Positive
    private Long courseId;

    @Size(max = 200)
    private String title;

    @NotBlank
    @Size(max = 500_000)
    private String markdown;
}
