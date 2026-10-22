package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 编程题一个测试用例的输入形态（JSON 导入 {@code cases[]} 与教师编辑接口共用）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeCaseItem {

    /** 标准输入（空/缺省视为空串） */
    private String stdin;

    /** 期望标准输出（必填，允许空串） */
    private String expectedStdout;

    /** 是否隐藏用例（缺省 false） */
    private Boolean hidden;
}
