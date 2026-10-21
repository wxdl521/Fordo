package com.wenjin.dto;

import lombok.Data;

/**
 * {@code AdminCourseMapper} 的列表筛选参数。keyword 已由服务层转义 LIKE 通配符；
 * offset/size 为服务端钳制后的整数。
 */
@Data
public class AdminCourseQuery {

    /** 已转义的关键字（匹配课程名/编码）；null=不筛 */
    private String keyword;
    /** 1/0；null=不筛 */
    private Integer status;
    /** true=仅归档, false=仅未归档；null=全部 */
    private Boolean archived;
    private Long teacherId;
    private long offset;
    private int size;
}
