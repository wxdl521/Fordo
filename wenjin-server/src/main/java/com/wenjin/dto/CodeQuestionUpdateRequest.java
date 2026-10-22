package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 教师编辑编程题：起始代码 + 全量替换的测试用例（至少 1 个）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeQuestionUpdateRequest {

    /** 起始代码（可空串/null=清空） */
    private String starterCode;

    /** 全量替换的用例列表（按顺序重新编号 seq=1..n） */
    private List<CodeCaseItem> cases;
}
