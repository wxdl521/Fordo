package com.wenjin.ai;

/** 多模态识图:图片 → 保留结构的 Markdown 文本(只还原不归纳)。 */
public interface ImageToMarkdownAiClient {
    String toMarkdown(byte[] jpegImageBytes);
}
