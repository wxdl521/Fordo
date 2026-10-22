package com.wenjin.sysconfig;

import com.wenjin.config.AiProperties;
import com.wenjin.config.CodeCheckProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 白名单清单守护：新增 / 删除可编辑 key 必须同步改这里（并先核实调用点运行时读取）。
 */
class ConfigRegistryTest {

    private static final List<String> EXPECTED_KEYS = List.of(
            "wenjin.ai.enabled",
            "wenjin.ai.base-url",
            "wenjin.ai.model",
            "wenjin.ai.api-key",
            "wenjin.ai.temperature",
            "wenjin.ai.vision.enabled",
            "wenjin.ai.vision.base-url",
            "wenjin.ai.vision.model",
            "wenjin.ai.vision.api-key",
            "wenjin.ai.embedding.enabled",
            "wenjin.ai.embedding.base-url",
            "wenjin.ai.embedding.model",
            "wenjin.ai.embedding.api-key",
            "wenjin.ai.embedding.dimension",
            "wenjin.ai.embedding.batch-size",
            "wenjin.features.fixed-diagnostic-enabled",
            "wenjin.features.graph-reconcile-enabled",
            "wenjin.features.ai-job-dispatch-enabled",
            "wenjin.features.agent-trace-enabled",
            "wenjin.features.memory-enabled",
            "wenjin.features.rag-enabled",
            "wenjin.features.rag-vector-enabled",
            "wenjin.features.reflection-enabled",
            "wenjin.features.companion-llm-enabled",
            "wenjin.features.plan-enabled",
            "wenjin.features.multi-agent-enabled",
            "wenjin.features.proactive-enabled",
            "wenjin.features.code-check-enabled",
            "wenjin.features.teacher-assistant-enabled",
            "wenjin.limits.companion.max-input-chars",
            "wenjin.limits.companion.max-ai-output-chars",
            "wenjin.limits.companion.max-context-chars",
            "wenjin.limits.companion.max-recent-messages",
            "wenjin.code-check.judge0.base-url",
            "wenjin.code-check.judge0.api-key");

    private final ConfigRegistry registry = new ConfigRegistry();

    @Test
    @DisplayName("白名单恰好是约定清单（按展示顺序）；eval-enabled 启动时决定，不在其中")
    void whitelist_matchesInventory() {
        assertThat(registry.all()).extracting(ConfigKeyDef::key).containsExactlyElementsOf(EXPECTED_KEYS);
        assertThat(registry.find("wenjin.features.eval-enabled")).isEmpty();
        assertThat(registry.find("wenjin.auth.secret")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    @DisplayName("分组顺序：AI 文本 → AI 视觉 → AI 嵌入 → 功能开关 → 限额 → 代码沙箱；只有四个 apiKey 是 secret")
    void groupsAndSecrets() {
        assertThat(registry.all()).extracting(ConfigKeyDef::group).containsSubsequence(
                ConfigRegistry.GROUP_AI_TEXT, ConfigRegistry.GROUP_AI_VISION,
                ConfigRegistry.GROUP_AI_EMBEDDING, ConfigRegistry.GROUP_FEATURES, ConfigRegistry.GROUP_LIMITS,
                ConfigRegistry.GROUP_CODE_CHECK);
        assertThat(registry.all()).filteredOn(ConfigKeyDef::isSecret).extracting(ConfigKeyDef::key)
                .containsExactly("wenjin.ai.api-key", "wenjin.ai.vision.api-key", "wenjin.ai.embedding.api-key",
                        "wenjin.code-check.judge0.api-key");
        assertThat(registry.all()).allSatisfy(d -> {
            assertThat(d.label()).isNotBlank();
            assertThat(d.description()).isNotBlank();
        });
    }

    @Test
    @DisplayName("max-input-chars 上限不超过 CompanionChatRequest 的 @Size(max=4000)；温度 0–2 可空")
    void ranges() {
        ConfigKeyDef input = registry.find("wenjin.limits.companion.max-input-chars").orElseThrow();
        assertThat(input.max().intValue()).isLessThanOrEqualTo(4000);
        ConfigKeyDef temp = registry.find("wenjin.ai.temperature").orElseThrow();
        assertThat(temp.nullable()).isTrue();
        assertThat(temp.min().doubleValue()).isZero();
        assertThat(temp.max().doubleValue()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("每个 key 的 reader / applier 指向同一个 bean 字段：写入合法值后读回相同")
    void readerApplier_roundTrip() {
        ConfigBeans beans = new ConfigBeans(new AiProperties(), new FeatureFlagsProperties(), new LimitsProperties(),
                new CodeCheckProperties());
        for (ConfigKeyDef def : registry.all()) {
            Object sample = sample(def);
            def.apply(beans, sample);
            assertThat(def.read(beans)).as(def.key()).isEqualTo(sample);
        }
        // 抽查确实落在了目标字段上
        assertThat(beans.ai().getVision().getModel()).isEqualTo("sample-wenjin.ai.vision.model");
        assertThat(beans.features().isCompanionLlmEnabled()).isFalse();
        assertThat(beans.limits().getCompanion().getMaxRecentMessages()).isEqualTo(1);
        assertThat(beans.features().isCodeCheckEnabled()).isFalse();
        assertThat(beans.codeCheck().getJudge0().getApiKey()).isEqualTo("sk-sample-wenjin.code-check.judge0.api-key");
    }

    @Test
    @DisplayName("被热改的 properties 字段都是 volatile（写入后其它线程立即可见）")
    void hotFields_areVolatile() throws Exception {
        assertVolatile(AiProperties.class, "enabled", "baseUrl", "apiKey", "model", "temperature");
        assertVolatile(AiProperties.Vision.class, "enabled", "baseUrl", "apiKey", "model");
        assertVolatile(AiProperties.Embedding.class, "enabled", "baseUrl", "apiKey", "model", "dimension", "batchSize");
        assertVolatile(FeatureFlagsProperties.class, "fixedDiagnosticEnabled", "graphReconcileEnabled",
                "aiJobDispatchEnabled", "agentTraceEnabled", "memoryEnabled", "ragEnabled", "reflectionEnabled",
                "companionLlmEnabled", "planEnabled", "multiAgentEnabled", "proactiveEnabled", "codeCheckEnabled",
                "teacherAssistantEnabled");
        assertVolatile(CodeCheckProperties.Judge0.class, "baseUrl", "apiKey");
        assertVolatile(LimitsProperties.Companion.class, "maxInputChars", "maxAiOutputChars",
                "maxContextChars", "maxRecentMessages");
    }

    private static Object sample(ConfigKeyDef def) {
        return switch (def.type()) {
            case BOOL -> Boolean.FALSE;
            case NUMBER -> def.integer() ? (Object) def.min().intValue() : (Object) def.max().doubleValue();
            case STRING -> def.urlOnly() ? "https://sample.example/" + def.key() : "sample-" + def.key();
            case SECRET -> "sk-sample-" + def.key();
            case ENUM -> def.options().get(0);
        };
    }

    private static void assertVolatile(Class<?> type, String... fields) throws Exception {
        for (String name : fields) {
            Field f = type.getDeclaredField(name);
            assertThat(Modifier.isVolatile(f.getModifiers())).as(type.getSimpleName() + "." + name).isTrue();
        }
    }
}
