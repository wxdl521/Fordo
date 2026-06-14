package com.wenjin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 图谱导入校验结果。校验失败时作为错误明细随 Result 返回，便于前端/调用方定位问题。
 */
@Data
public class GraphValidateResult {

    /** 逐条问题明细 */
    private List<Issue> issues = new ArrayList<>();

    /** 是否通过 */
    public boolean isValid() {
        return issues.isEmpty();
    }

    public void add(String category, String message) {
        issues.add(new Issue(category, message));
    }

    /** 单条问题 */
    @Data
    public static class Issue {
        /** 问题类别：DUPLICATE_NODE_ID / MISSING_NODE / CYCLE / BAD_RELATION_TYPE / EMPTY 等 */
        private final String category;
        /** 可定位的中文描述 */
        private final String message;
    }
}
