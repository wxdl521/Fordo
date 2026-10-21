package com.wenjin.dto;

/**
 * 误区节点命中汇总：近期错题中所选错误选项指向该节点的次数。
 */
public record MisconceptionSummary(String nodeCode, String name, int hits) {
}
