package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 管理后台用户分页结果。
 */
@Data
public class AdminUserPageVO {

    private List<AdminUserVO> records;
    /** 满足筛选条件的总条数 */
    private long total;
    /** 实际生效的页码（从 1 起） */
    private int page;
    /** 实际生效的每页条数 */
    private int size;
}
