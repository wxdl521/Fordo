package com.wenjin.dto;

import lombok.Data;

import java.util.Map;

/**
 * 学习数据重置结果。
 * <p>{@code deleted}：表名 → 删除行数（按实际删除顺序）；{@code currentPathCleared}：
 * student_course.current_path_id 被置空的选课行数（选课本身保留）。</p>
 */
@Data
public class AdminDataResetVO {

    private Map<String, Integer> deleted;
    private int currentPathCleared;

    public AdminDataResetVO() {}

    public AdminDataResetVO(Map<String, Integer> deleted, int currentPathCleared) {
        this.deleted = deleted;
        this.currentPathCleared = currentPathCleared;
    }
}
