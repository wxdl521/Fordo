package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/** 教师端 Agent run 分页结果。 */
@Data
public class AgentRunPageVO {

    private long total;
    private int page;
    private int size;
    private List<AgentRunVO> items;
}
