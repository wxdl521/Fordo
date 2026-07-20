package com.wenjin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProbeSubmitRequest {

    @NotNull
    private Long studentId;

    @NotNull
    private Long courseId;

    @NotBlank
    private String studentAnswer;
}
