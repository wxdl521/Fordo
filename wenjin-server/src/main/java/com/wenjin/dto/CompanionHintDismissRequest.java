package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** POST /api/companion/hints/dismiss 请求体。 */
@Data
public class CompanionHintDismissRequest {

    @NotNull
    @Positive
    private Long courseId;

    @NotBlank
    @Size(max = 96)
    private String hintKey;
}
