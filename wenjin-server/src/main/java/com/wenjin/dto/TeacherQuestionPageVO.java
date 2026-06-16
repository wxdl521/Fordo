package com.wenjin.dto;

import lombok.Data;

import java.util.List;

@Data
public class TeacherQuestionPageVO {
    private Long total;
    private Integer page;
    private Integer size;
    private List<TeacherQuestionVO> items;
    private Counts counts;

    @Data
    public static class Counts {
        private Long pending;
        private Long passed;
        private Long rejected;
    }
}
