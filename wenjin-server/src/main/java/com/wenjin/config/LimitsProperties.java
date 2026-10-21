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
    private final Prompt prompt = new Prompt();
    private final Memory memory = new Memory();
    private final Rag rag = new Rag();
    private final Draft draft = new Draft();
    private final Image image = new Image();
    private final Document document = new Document();
    private final Trace trace = new Trace();
    private final Tools tools = new Tools();
    private final Plan plan = new Plan();
    private final Supervisor supervisor = new Supervisor();

    /**
     * 伴侣会话预算（wenjin.limits.companion.*）。前四项每次请求现读，可由管理后台「系统配置」运行时改写，
     * 故标 {@code volatile}。
     */
    @Data
    public static class Companion {
        /** 单条用户输入最大字符数（接口 DTO 另有 @Size(max=4000) 硬上限，调大超过 4000 无效） */
        private volatile int maxInputChars = 4000;
        /** 单轮 AI 累计输出最大字符数（超出截断并附原因） */
        private volatile int maxAiOutputChars = 16_000;
        /** 送入模型的上下文总字符上限（摘要 + 近窗） */
        private volatile int maxContextChars = 32_000;
        /** 近窗消息条数（摘要之外保留的最新消息） */
        private volatile int maxRecentMessages = 20;
        /** 会话/消息列表默认 limit */
        private int listDefaultLimit = 30;
        /** 会话/消息列表 limit 上限 */
        private int listMaxLimit = 100;
    }

    /**
     * PromptAssembler 分槽字符预算（wenjin.limits.prompt.*）。
     * 各槽独立上限；总长度仍受 {@link Companion#maxContextChars} 约束。
     */
    @Data
    public static class Prompt {
        private int systemChars = 8_000;
        private int skillChars = 2_000;
        private int summaryChars = 8_000;
        private int memoryChars = 4_000;
        private int retrievalChars = 4_000;
        private int recentChars = 16_000;
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

    /**
     * 长期记忆读时强度与去重（wenjin.limits.memory.*，M7）。
     * effective = salience × exp(−λ · Δdays)。
     */
    @Data
    public static class Memory {
        /** 遗忘系数 λ（天⁻¹）；默认 0.05 */
        private double lambda = 0.05;
        /** Jaccard 同义判定阈值；≥ 此值视为同义并强化已有记忆 */
        private double jaccardThreshold = 0.6;
        /** content 写入硬上限（与列 VARCHAR(500) 对齐，蒸馏侧再夹到 200） */
        private int maxContentChars = 500;
        /** MEMORY 槽注入 top-k（按 effective 全 kind 混排） */
        private int injectTopK = 5;
        /** record_observation 每 run 写入上限 */
        private int writePerRun = 2;
    }

    /**
     * RAG 检索与注入（wenjin.limits.rag.*，M8）。
     */
    @Data
    public static class Rag {
        /** RETRIEVAL / get_node_detail 引用注入 top-k */
        private int injectTopK = 4;
        /** search_materials 默认 topK */
        private int searchDefaultTopK = 3;
        /** 检索 snippet 最大字符 */
        private int snippetChars = 200;
    }

    @Data
    public static class Trace {
        /** agent_step 输入/输出最大字符；超出截断并追加 [truncated] */
        private int maxIoChars = 8000;
    }

    @Data
    public static class Tools {
        /** 工具结果字符上限（Phase 3 tool cache 等） */
        private int maxResultChars = 4000;
    }

    /**
     * 学习计划算法与 brief（wenjin.limits.plan.*，M10）。
     * <p>effective / weak-threshold 使用与掌握度一致的 0–100 分口径；
     * 规格文中 0.6 对应本默认 60。
     */
    @Data
    public static class Plan {
        /** 每日任务上限 */
        private int dailyTaskCap = 3;
        /** weak 判定：effective &lt; 此值（0–100） */
        private double weakThreshold = 60.0;
        /** daily-brief 复习积压数阈值（&gt; 此值置顶提醒） */
        private int backlogThreshold = 5;
        /** draft_study_plan scopeNodes 上限 */
        private int maxScopeNodes = 42;
        /** brief 中「有效分新跌破阈值」最多展示节点数 */
        private int weakDropLimit = 2;
    }

    /**
     * Supervisor 抽查（wenjin.limits.supervisor.*，M11 可选）。
     */
    @Data
    public static class Supervisor {
        /** 每课程每周抽样 COMPANION_CHAT run 数 */
        private int sampleSize = 5;
    }
}
