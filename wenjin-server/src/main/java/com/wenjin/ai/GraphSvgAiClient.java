package com.wenjin.ai;

/**
 * 知识图谱预览 SVG 生成 AI 客户端：把图谱画成一张完整 SVG（文本）。
 */
public interface GraphSvgAiClient {

    /** 给定完整 prompt，返回剥离围栏后的 SVG 文本。 */
    String generate(String prompt);
}
