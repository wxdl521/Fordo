package com.wenjin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 资源预算：伴侣上下文、草稿容量、图片/文档处理上限。
 */
@Data
@ConfigurationProperties(prefix = "wenjin.limits")
public class LimitsProperties {

    private final Companion companion = new Companion();
    private final Draft draft = new Draft();
    private final Image image = new Image();
    private final Document document = new Document();

    @Data
    public static class Companion {
        /** 单条用户输入最大字符数 */
        private int maxInputChars = 4000;
        /** 单轮 AI 累计输出最大字符数（超出截断并附原因） */
        private int maxAiOutputChars = 16_000;
        /** 送入模型的上下文总字符上限（摘要 + 近窗） */
        private int maxContextChars = 32_000;
        /** 近窗消息条数（摘要之外保留的最新消息） */
        private int maxRecentMessages = 20;
        /** 会话/消息列表默认 limit */
        private int listDefaultLimit = 30;
        /** 会话/消息列表 limit 上限 */
        private int listMaxLimit = 100;
    }

    @Data
    public static class Draft {
        private int maxTotal = 100;
        private int maxPerUser = 10;
        private int maxNodes = 2000;
        private int maxEdges = 5000;
        private int ttlMinutes = 30;
        /** 定时清理间隔（毫秒） */
        private long purgeIntervalMs = 60_000L;
        /** 单草稿字节估算上限（防超大 payload 占满内存） */
        private long maxBytes = 8_000_000L;
    }

    @Data
    public static class Image {
        private int maxDimension = 10_000;
        private long maxPixels = 40_000_000L;
        private int maxPredictedTiles = 12;
        private int targetWidth = 1600;
        private int tileHeight = 2200;
        private int tileOverlap = 120;
    }

    @Data
    public static class Document {
        private int maxPages = 200;
        private int maxTextChars = 200_000;
        /** 解压膨胀比上限（防 zip bomb） */
        private int maxDecompressRatio = 100;
    }
}
