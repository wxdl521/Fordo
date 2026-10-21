package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/** 系统配置分组（AI 文本 / AI 视觉 / 功能开关 / 限额）。 */
@Data
public class AdminConfigGroupVO {

    private String group;
    private List<AdminConfigItemVO> items;

    public AdminConfigGroupVO() {}

    public AdminConfigGroupVO(String group, List<AdminConfigItemVO> items) {
        this.group = group;
        this.items = items;
    }
}
