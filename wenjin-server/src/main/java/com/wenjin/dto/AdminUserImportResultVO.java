package com.wenjin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量导入结果。导入"全有或全无"：errors 非空时 created 必为 0 且一行都没写入。
 */
@Data
public class AdminUserImportResultVO {

    /** 成功创建的用户数 */
    private int created;
    /** 错误行（Excel 行号，表头为第 1 行） */
    private List<RowError> errors = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowError {
        private int row;
        private String username;
        private String message;
    }
}
