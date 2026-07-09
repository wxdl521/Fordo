package com.wenjin.support;

/**
 * 伴侣练习意图：节点标识（nodeCode / nodeName 至少一个非 blank）与题量 size（1..3）。
 */
public record PracticeIntent(String nodeCode, String nodeName, int size) {
}
