package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TeacherQuestionVO {
    private Long id;
    private String stem;
    private Integer type;
    private Integer difficulty;
    private List<OptionVO> options;
    private String mainNodeCode;
    private String mainNodeName;
    private Integer confidence;
    private Integer status;
    private Integer source;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @Data
    public static class OptionVO {
        private String key;
        private String text;
        private Boolean correct;
        private String pointNodeCode;
    }
}
