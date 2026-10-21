package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 日志类分页结果：{@code { records, total, page, size }}（与 {@link AdminUserPageVO} 同形）。
 */
@Data
public class LogPageVO<T> {

    private List<T> records;
    /** 满足筛选条件的总条数 */
    private long total;
    /** 实际生效的页码（从 1 起） */
    private int page;
    /** 实际生效的每页条数 */
    private int size;
}
