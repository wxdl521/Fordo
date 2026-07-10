package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class QuestionReviewRequest {

    @NotNull(message = "ids 不能为空")
    @Size(min = 1, max = 500, message = "批量审核数量须在 1-500 之间")
    private List<@NotNull @Positive Long> ids;

    @NotBlank(message = "action 不能为空")
    private String action; // "pass" or "reject"
}
