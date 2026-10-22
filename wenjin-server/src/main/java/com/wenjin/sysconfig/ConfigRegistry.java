package com.wenjin.sysconfig;

import com.wenjin.config.AiProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 可编辑配置白名单。只有这里登记的 key 能被管理后台修改；不做"任意 key 编辑器"。
 *
 * <p><b>登记条件：调用点必须每次现读 properties bean</b>（改 bean 字段即热生效，字段已标 volatile）。
 * 核实结论（2026-10-21）：</p>
 * <ul>
 *   <li>AI 文本 / 视觉：OpenAiCompatible*Client 每次调用读 {@code getBaseUrl/getApiKey/getModel/getTemperature/isEnabled}。</li>
 *   <li>功能开关：10 个开关的调用点都是每次 {@code featureFlags.isXxx()}；
 *       <b>不含</b> {@code eval-enabled}（{@code EvalConfiguration} 的 {@code @ConditionalOnProperty} 启动时决定）。</li>
 *   <li>限额：{@code wenjin.limits.companion.*} 四项在 CompanionServiceImpl / ConversationContextBuilder /
 *       PromptAssembler 每次请求现读。{@code max-input-chars} 另受 {@code CompanionChatRequest} 的
 *       {@code @Size(max=4000)} 约束，所以上限定为 4000（调大无效）。</li>
 *   <li>不登记：{@code @Scheduled} 的 cron / 间隔（如 draft.purge-interval-ms）、{@code @Value} 启动时注入的
 *       （diagnostic.paper-size、practice.size 等）——改了不生效。</li>
 * </ul>
 */
@Component
public class ConfigRegistry {

    public static final String GROUP_AI_TEXT = "AI 文本";
    public static final String GROUP_AI_VISION = "AI 视觉";
    public static final String GROUP_AI_EMBEDDING = "AI 嵌入";
    public static final String GROUP_FEATURES = "功能开关";
    public static final String GROUP_LIMITS = "限额";

    /** 与 OpenAiCompatible*Client 的 trimTrailingSlash 拼接一致：base-url 不宜过长 */
    private static final int URL_MAX = 255;
    private static final int MODEL_MAX = 64;
    private static final int SECRET_MAX = 512;

    private final Map<String, ConfigKeyDef> defs;

    public ConfigRegistry() {
        List<ConfigKeyDef> list = new ArrayList<>();

        // ── AI 文本（wenjin.ai.*）──
        list.add(ConfigKeyDef.bool("wenjin.ai.enabled", GROUP_AI_TEXT, "启用文本模型",
                "关闭后出题、标注、清洗、图谱抽取、学习伴侣等文本 AI 调用全部直接失败",
                b -> b.ai().isEnabled(), (b, v) -> b.ai().setEnabled(v)));
        list.add(ConfigKeyDef.text("wenjin.ai.base-url", GROUP_AI_TEXT, "服务基址",
                "OpenAI 兼容接口基址，不含 /v1，如 https://api.deepseek.com", URL_MAX, true,
                b -> b.ai().getBaseUrl(), (b, v) -> b.ai().setBaseUrl(v)));
        list.add(ConfigKeyDef.text("wenjin.ai.model", GROUP_AI_TEXT, "模型名",
                "如 deepseek-v4-pro；保存后下一次 AI 请求即用新模型", MODEL_MAX, false,
                b -> b.ai().getModel(), (b, v) -> b.ai().setModel(v)));
        list.add(ConfigKeyDef.secret("wenjin.ai.api-key", GROUP_AI_TEXT, "API Key",
                "加密存储，界面只显示末 4 位", SECRET_MAX,
                b -> b.ai().getApiKey(), (b, v) -> b.ai().setApiKey(v)));
        list.add(ConfigKeyDef.decimal("wenjin.ai.temperature", GROUP_AI_TEXT, "采样温度",
                "0–2；留空则各功能用内置默认（出题 0.4 / 伴侣 0.7）", 0, 2, true,
                b -> b.ai().getTemperature(), (b, v) -> b.ai().setTemperature(v)));

        // ── AI 视觉（wenjin.ai.vision.*）──
        list.add(ConfigKeyDef.bool("wenjin.ai.vision.enabled", GROUP_AI_VISION, "启用视觉模型",
                "课程标准图片识别（图片转 Markdown）；关闭后图片抽取直接失败",
                b -> vision(b).isEnabled(), (b, v) -> vision(b).setEnabled(v)));
        list.add(ConfigKeyDef.text("wenjin.ai.vision.base-url", GROUP_AI_VISION, "服务基址",
                "到 /chat/completions 之前的完整前缀，如 https://open.bigmodel.cn/api/paas/v4", URL_MAX, true,
                b -> vision(b).getBaseUrl(), (b, v) -> vision(b).setBaseUrl(v)));
        list.add(ConfigKeyDef.text("wenjin.ai.vision.model", GROUP_AI_VISION, "模型名",
                "如 glm-5v-turbo", MODEL_MAX, false,
                b -> vision(b).getModel(), (b, v) -> vision(b).setModel(v)));
        list.add(ConfigKeyDef.secret("wenjin.ai.vision.api-key", GROUP_AI_VISION, "API Key",
                "加密存储，界面只显示末 4 位", SECRET_MAX,
                b -> vision(b).getApiKey(), (b, v) -> vision(b).setApiKey(v)));

        // ── AI 嵌入（wenjin.ai.embedding.*；OpenAiCompatibleEmbeddingClient 每次调用现读）──
        list.add(ConfigKeyDef.bool("wenjin.ai.embedding.enabled", GROUP_AI_EMBEDDING, "启用嵌入模型",
                "RAG 向量检索的文本嵌入；关闭或无 Key 时检索回退纯全文",
                b -> embedding(b).isEnabled(), (b, v) -> embedding(b).setEnabled(v)));
        list.add(ConfigKeyDef.text("wenjin.ai.embedding.base-url", GROUP_AI_EMBEDDING, "服务基址",
                "到 /embeddings 之前的完整前缀，如 https://open.bigmodel.cn/api/paas/v4", URL_MAX, true,
                b -> embedding(b).getBaseUrl(), (b, v) -> embedding(b).setBaseUrl(v)));
        list.add(ConfigKeyDef.text("wenjin.ai.embedding.model", GROUP_AI_EMBEDDING, "模型名",
                "如 embedding-3；更换模型或维度后需在教师端「重建向量」", MODEL_MAX, false,
                b -> embedding(b).getModel(), (b, v) -> embedding(b).setModel(v)));
        list.add(ConfigKeyDef.secret("wenjin.ai.embedding.api-key", GROUP_AI_EMBEDDING, "API Key",
                "加密存储，界面只显示末 4 位", SECRET_MAX,
                b -> embedding(b).getApiKey(), (b, v) -> embedding(b).setApiKey(v)));
        list.add(ConfigKeyDef.integer("wenjin.ai.embedding.dimension", GROUP_AI_EMBEDDING, "向量维度",
                "请求 dimensions 并校验返回长度；embedding-3 支持 256/512/1024/2048", 64, 4096,
                b -> embedding(b).getDimension(), (b, v) -> embedding(b).setDimension(v)));
        list.add(ConfigKeyDef.integer("wenjin.ai.embedding.batch-size", GROUP_AI_EMBEDDING, "批大小",
                "单次嵌入请求最多条数", 1, 64,
                b -> embedding(b).getBatchSize(), (b, v) -> embedding(b).setBatchSize(v)));

        // ── 功能开关（wenjin.features.*，不含 eval-enabled）──
        feature(list, "fixed-diagnostic-enabled", "固定卷诊断", "组卷冻结 + 精确交卷",
                FeatureFlagsProperties::isFixedDiagnosticEnabled, FeatureFlagsProperties::setFixedDiagnosticEnabled);
        feature(list, "graph-reconcile-enabled", "图谱对账 / 修复", "图谱对账与修复任务",
                FeatureFlagsProperties::isGraphReconcileEnabled, FeatureFlagsProperties::setGraphReconcileEnabled);
        feature(list, "ai-job-dispatch-enabled", "AI 异步作业派发", "关闭后不再受理与领取 AI 异步作业",
                FeatureFlagsProperties::isAiJobDispatchEnabled, FeatureFlagsProperties::setAiJobDispatchEnabled);
        feature(list, "agent-trace-enabled", "Agent 执行轨迹", "agent_run / agent_step 埋点与教师端轨迹查看",
                FeatureFlagsProperties::isAgentTraceEnabled, FeatureFlagsProperties::setAgentTraceEnabled);
        feature(list, "memory-enabled", "伴侣长期记忆", "关闭时伴侣以空记忆工作",
                FeatureFlagsProperties::isMemoryEnabled, FeatureFlagsProperties::setMemoryEnabled);
        feature(list, "rag-enabled", "RAG 语料检索", "关闭时不注入检索片段、search_materials 下架",
                FeatureFlagsProperties::isRagEnabled, FeatureFlagsProperties::setRagEnabled);
        feature(list, "rag-vector-enabled", "RAG 混合检索（向量）",
                "需 RAG 语料检索同时开启；关闭或无可用嵌入模型时检索退回纯全文，入库后不再派发向量作业",
                FeatureFlagsProperties::isRagVectorEnabled, FeatureFlagsProperties::setRagVectorEnabled);
        feature(list, "reflection-enabled", "反思探针", "关闭时不下发理解探针、不换策略重讲",
                FeatureFlagsProperties::isReflectionEnabled, FeatureFlagsProperties::setReflectionEnabled);
        feature(list, "companion-llm-enabled", "伴侣 LLM", "紧急开关：关闭后伴侣立即走零 LLM 降级模板",
                FeatureFlagsProperties::isCompanionLlmEnabled, FeatureFlagsProperties::setCompanionLlmEnabled);
        feature(list, "plan-enabled", "学习计划", "关闭时计划工具下架、re-plan 不跑、简报隐藏计划段",
                FeatureFlagsProperties::isPlanEnabled, FeatureFlagsProperties::setPlanEnabled);
        feature(list, "multi-agent-enabled", "多智能体周报 / 抽查", "关闭则零调度、报告接口 404",
                FeatureFlagsProperties::isMultiAgentEnabled, FeatureFlagsProperties::setMultiAgentEnabled);
        feature(list, "proactive-enabled", "主动陪伴", "关闭时伴侣 opener / 提示条 / 小红点接口返回 404，前端回退固定话术",
                FeatureFlagsProperties::isProactiveEnabled, FeatureFlagsProperties::setProactiveEnabled);

        // ── 限额（wenjin.limits.companion.*）──
        list.add(ConfigKeyDef.integer("wenjin.limits.companion.max-input-chars", GROUP_LIMITS, "单条输入上限（字）",
                "学生单条消息最大字符数；接口另有 4000 字硬上限", 100, 4000,
                b -> companion(b).getMaxInputChars(), (b, v) -> companion(b).setMaxInputChars(v)));
        list.add(ConfigKeyDef.integer("wenjin.limits.companion.max-ai-output-chars", GROUP_LIMITS, "单轮输出上限（字）",
                "单轮 AI 回复累计字符上限，超出截断", 1000, 100_000,
                b -> companion(b).getMaxAiOutputChars(), (b, v) -> companion(b).setMaxAiOutputChars(v)));
        list.add(ConfigKeyDef.integer("wenjin.limits.companion.max-context-chars", GROUP_LIMITS, "上下文上限（字）",
                "送入模型的摘要 + 近窗总字符上限", 8000, 200_000,
                b -> companion(b).getMaxContextChars(), (b, v) -> companion(b).setMaxContextChars(v)));
        list.add(ConfigKeyDef.integer("wenjin.limits.companion.max-recent-messages", GROUP_LIMITS, "近窗消息条数",
                "摘要之外原样保留的最新消息条数", 1, 100,
                b -> companion(b).getMaxRecentMessages(), (b, v) -> companion(b).setMaxRecentMessages(v)));

        Map<String, ConfigKeyDef> map = new LinkedHashMap<>();
        for (ConfigKeyDef def : list) {
            if (map.put(def.key(), def) != null) {
                throw new IllegalStateException("重复登记的配置 key: " + def.key());
            }
        }
        this.defs = Collections.unmodifiableMap(map);
    }

    /** 全部登记项（登记顺序 = 页面展示顺序）。 */
    public List<ConfigKeyDef> all() {
        return List.copyOf(defs.values());
    }

    public Optional<ConfigKeyDef> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(defs.get(key));
    }

    private static void feature(List<ConfigKeyDef> list, String name, String label, String description,
                                Function<FeatureFlagsProperties, Boolean> get,
                                BiConsumer<FeatureFlagsProperties, Boolean> set) {
        list.add(ConfigKeyDef.bool("wenjin.features." + name, GROUP_FEATURES, label, description,
                b -> get.apply(b.features()), (b, v) -> set.accept(b.features(), v)));
    }

    private static AiProperties.Vision vision(ConfigBeans b) {
        return b.ai().getVision();
    }

    private static AiProperties.Embedding embedding(ConfigBeans b) {
        return b.ai().getEmbedding();
    }

    private static LimitsProperties.Companion companion(ConfigBeans b) {
        return b.limits().getCompanion();
    }
}
