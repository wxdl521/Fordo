package com.wenjin.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 批量加学生：{@code studentIds} 或 {@code usernames} 二选一（服务层校验），各至多 500 个。 */
@Data
public class ClazzAddStudentsRequest {

    @Size(max = 500, message = "一次最多添加 500 人")
    private List<Long> studentIds;

    @Size(max = 500, message = "一次最多添加 500 人")
    private List<String> usernames;
}
