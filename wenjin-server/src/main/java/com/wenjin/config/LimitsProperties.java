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
    private final Proactive proactive = new Proactive();
    private final Code code = new Code();

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
        /** RRF 融合常数 k：score = Σ 1/(k + rank)（R2） */
        private int fusionK = 60;
        /** 混合检索时全文 / 向量各取 topK × 此倍数作为候选（R2） */
        private int candidateMultiplier = 3;
        /** 向量命中的最低余弦相似度；低于此值不进入候选（R2） */
        private double vectorMinScore = 0.2;
        /**
         * 仅向量命中（不在全文候选里）的块须达到的更高余弦下限（R2 复核）。同时被全文命中的块沿用
         * {@link #vectorMinScore}。余弦分布随模型而异，启用向量前须按模型标定（见 DEPLOYMENT.md）。
         */
        private double vectorOnlyMinScore = 0.5;
        /** 在线查询嵌入的读超时（毫秒）；批量 RAG_EMBED 仍用 60s（R2 复核）。 */
        private int queryEmbedTimeoutMs = 2500;
        /** 向量侧失败（超时 / 报错）后跳过向量检索、纯全文的冷却秒数；0 关闭（R2 复核）。 */
        private int vectorCooldownSeconds = 60;
        /**
         * 无真实嵌入模型时是否允许离线 HashingEmbeddingClient 作为向量后端（R2）。
         * Java 默认 false；开发（application.yml）/ e2e 显式置 true（可无 key 演示混合检索），生产保持 false。
         */
        private boolean allowHashingEmbedding = false;
        /**
         * INGESTING 来源超过此分钟数仍未结束视为卡死，不再计入上传去重（须大于租约 × 最大领取次数 = 45 分钟）。
         */
        private int ingestStaleMinutes = 60;
        /** 查询向量 LRU 缓存条数（按 modelId+查询串）；0 关闭（R2） */
        private int queryCacheSize = 512;
        /** 伴侣 RETRIEVAL 注入的节点范围是否扩展到焦点节点的直接前置（R2） */
        private boolean expandPrerequisites = true;
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

    /**
     * 主动陪伴（wenjin.limits.proactive.*，方向 4）。
     */
    @Data
    public static class Proactive {
        /** opener 最多返回的动作卡数 */
        private int maxOpenerActions = 2;
        /** 每日（自然日）最多新展示的提示条数 */
        private int maxHintsPerDay = 3;
        /** 同类型提示的冷却小时数（新实例须距同类型上次展示超过此值） */
        private int hintCooldownHours = 20;
        /** 单次练习错题数 >= 此值视为错题爆发 */
        private int errorBurstThreshold = 2;
        /** 错题爆发的回看窗口（小时） */
        private int errorBurstWindowHours = 24;
    }

    /**
     * 代码执行沙箱限额（wenjin.limits.code.*，方向 6）。每次执行现读（{@link #maxConcurrent} 除外：
     * 信号量在启动时按它创建）。
     */
    @Data
    public static class Code {
        /** 源码最大字符数 */
        private int sourceMaxChars = 10_000;
        /** 单题测试用例数上限（超出拒绝判分，不做部分判分） */
        private int maxCases = 20;
        /** 容器内 javac 墙钟上限（秒） */
        private int compileTimeoutSeconds = 15;
        /** 每个用例的运行墙钟上限（秒，含 JVM 启动） */
        private int runTimeoutSeconds = 3;
        /** 容器内存上限（MB；swap 同值即禁用 swap；/tmp tmpfs 也计入） */
        private int memoryMb = 256;
        /** javac / java 的 -Xmx（MB） */
        private int jvmHeapMb = 128;
        /** 容器 CPU 配额（核） */
        private double cpus = 1.0;
        /** 容器进程/线程数上限（防 fork / 线程炸弹） */
        private int pids = 64;
        /** 每个用例 stdout / stderr 及编译输出在容器内截断的字节数；超出判 RESOURCE_LIMIT */
        private int outputMaxBytes = 65_536;
        /** 结果里 stdout / stderr / 用例预览的字符数 */
        private int outputPreviewChars = 500;
        /** 结果里编译错误的字符数 */
        private int compileErrorChars = 2000;
        /** 全局并发执行数（信号量，启动时决定） */
        private int maxConcurrent = 2;
        /** 每学生每小时（滑动窗口）执行次数上限；≤0 不限 */
        private int perStudentPerHour = 30;
        /** 等待并发许可的秒数；超时返回 UNAVAILABLE（请稍后再试） */
        private int queueWaitSeconds = 10;
        /** 自由运行的标准输入字符上限 */
        private int stdinMaxChars = 65_536;

        // ---- 伴侣工具 run_code_check 层（源码上限复用上面的 sourceMaxChars） ----
        /** 每个 agent run 最多调用 run_code_check 的次数（含失败/被拒的调用） */
        private int toolMaxPerRun = 1;
        /** 工具 {@code stdin} 参数最大字符数 */
        private int toolStdinMaxChars = 2000;
        /** 回给模型的 stdout 预览字符数 */
        private int toolStdoutPreviewChars = 300;
        /** 回给模型的 stderr 预览字符数 */
        private int toolStderrPreviewChars = 200;
        /** 回给模型的编译错误行数 / 字符数上限（取先到者） */
        private int toolCompileErrorLines = 8;
        private int toolCompileErrorChars = 600;
        /** 回给模型的失败用例条数，以及每条用例每个字段的预览字符数 */
        private int toolMaxFailureCases = 3;
        private int toolCaseFieldChars = 100;
    }
}
