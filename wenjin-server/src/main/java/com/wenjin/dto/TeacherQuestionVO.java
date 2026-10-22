package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
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

    /** 编程题起始代码（仅 type=5 时出现） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String starterCode;

    /** 编程题语言（仅 type=5 时出现） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String codeLanguage;

    /** 编程题全部测试用例（含 hidden；教师可见；仅 type=5 时出现） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<CaseVO> cases;

    @Data
    public static class CaseVO {
        private Integer seq;
        private String stdin;
        private String expectedStdout;
        private Boolean hidden;
    }

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
