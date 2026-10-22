package com.wenjin.sysconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AiProperties;
import com.wenjin.config.CodeCheckProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.AdminConfigGroupVO;
import com.wenjin.dto.AdminConfigItemVO;
import com.wenjin.entity.SysConfig;
import com.wenjin.mapper.SysConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SysConfigService：覆盖 → bean 立即变；DELETE 恢复启动快照；secret 加密落库且不出接口；
 * 启动应用覆盖时白名单外 / 解密失败 / 不合规 / 读表失败都只跳过不崩。
 */
@ExtendWith(MockitoExtension.class)
class SysConfigServiceTest {

    private static final String PLAIN_KEY = "sk-live-9f8e7d6c5b4a3210";
    private static final String DEFAULT_KEY = "sk-from-env-11112222zzzz";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private SysConfigMapper mapper;

    private AiProperties ai;
    private FeatureFlagsProperties features;
    private LimitsProperties limits;
    private ConfigCrypto crypto;
    private SysConfigService service;

    @BeforeEach
    void setUp() {
        ai = new AiProperties();
        ai.setBaseUrl("https://api.deepseek.com");
        ai.setModel("deepseek-v4-pro");
        ai.setApiKey(DEFAULT_KEY);
        ai.setTemperature(0.4);
        ai.getVision().setModel("glm-5v-turbo");
        features = new FeatureFlagsProperties();
        limits = new LimitsProperties();
        crypto = new ConfigCrypto("", "dev-secret-change-me-in-prod", new String[]{"test"});
        service = new SysConfigService(mapper, new ConfigRegistry(), crypto, ai, features, limits,
                new CodeCheckProperties());
    }

    // ───────────────────────── list ─────────────────────────

    @Test
    @DisplayName("list：四个分组按顺序；普通项给当前值 + 默认值；secret 只有 configured + 掩码，序列化后不含明文")
    void list_groupsAndSecretMasking() throws Exception {
        SysConfig row = row("wenjin.ai.model", "gpt-x", 0);
        row.setUpdatedAt(LocalDateTime.of(2026, 10, 21, 9, 30, 0));
        row.setUpdatedBy(1L);
        when(mapper.selectList(any())).thenReturn(List.of(row));

        List<AdminConfigGroupVO> groups = service.list();

        assertThat(groups).extracting(AdminConfigGroupVO::getGroup)
                .containsExactly("AI 文本", "AI 视觉", "AI 嵌入", "功能开关", "限额", "代码沙箱");
        AdminConfigItemVO model = item(groups, "wenjin.ai.model");
        assertThat(model.getType()).isEqualTo("string");
        assertThat(model.getValue()).isEqualTo("deepseek-v4-pro");
        assertThat(model.getDefaultValue()).isEqualTo("deepseek-v4-pro");
        assertThat(model.isOverridden()).isTrue();
        assertThat(model.getUpdatedAt()).isEqualTo("2026-10-21T09:30:00");
        assertThat(model.getUpdatedBy()).isEqualTo(1L);

        AdminConfigItemVO key = item(groups, "wenjin.ai.api-key");
        assertThat(key.getType()).isEqualTo("secret");
        assertThat(key.getValue()).isNull();
        assertThat(key.getDefaultValue()).isNull();
        assertThat(key.getConfigured()).isTrue();
        assertThat(key.getMasked()).isEqualTo("zzzz");
        assertThat(key.isOverridden()).isFalse();
        AdminConfigItemVO visionKey = item(groups, "wenjin.ai.vision.api-key");
        assertThat(visionKey.getConfigured()).isFalse();
        assertThat(visionKey.getMasked()).isNull();

        AdminConfigItemVO temp = item(groups, "wenjin.ai.temperature");
        assertThat(temp.getType()).isEqualTo("number");
        assertThat(temp.getMin()).isEqualTo(0.0);
        assertThat(temp.getMax()).isEqualTo(2.0);
        assertThat(temp.getNullable()).isTrue();
        AdminConfigItemVO input = item(groups, "wenjin.limits.companion.max-input-chars");
        assertThat(input.getValue()).isEqualTo(4000);
        assertThat(input.getMax()).isEqualTo(4000);
        assertThat(input.getNullable()).isNull();
        assertThat(item(groups, "wenjin.features.companion-llm-enabled").getType()).isEqualTo("bool");

        String json = JSON.writeValueAsString(groups);
        assertThat(json).doesNotContain(DEFAULT_KEY).doesNotContain("11112222");
    }

    // ───────────────────────── update ─────────────────────────

    @Test
    @DisplayName("update bool：写库（明文 \"false\"、encrypted=0、记操作人）并立即改 bean")
    void update_bool_appliesImmediately() {
        assertThat(features.isCompanionLlmEnabled()).isTrue();
        when(mapper.selectById("wenjin.features.companion-llm-enabled"))
                .thenReturn(row("wenjin.features.companion-llm-enabled", "false", 0));

        AdminConfigItemVO vo = service.update("wenjin.features.companion-llm-enabled", false, 7L);

        verify(mapper).upsert("wenjin.features.companion-llm-enabled", "false", 0, 7L);
        assertThat(features.isCompanionLlmEnabled()).isFalse();
        assertThat(vo.getValue()).isEqualTo(false);
        assertThat(vo.getDefaultValue()).isEqualTo(true);
        assertThat(vo.isOverridden()).isTrue();
    }

    @Test
    @DisplayName("update number / string：规范化后写库、bean 立即变")
    void update_numberAndString() {
        service.update("wenjin.limits.companion.max-input-chars", "2000", 1L);
        verify(mapper).upsert("wenjin.limits.companion.max-input-chars", "2000", 0, 1L);
        assertThat(limits.getCompanion().getMaxInputChars()).isEqualTo(2000);

        service.update("wenjin.ai.model", "  deepseek-chat ", 1L);
        verify(mapper).upsert("wenjin.ai.model", "deepseek-chat", 0, 1L);
        assertThat(ai.getModel()).isEqualTo("deepseek-chat");

        service.update("wenjin.ai.temperature", 0.9, 1L);
        verify(mapper).upsert("wenjin.ai.temperature", "0.9", 0, 1L);
        assertThat(ai.getTemperature()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("update temperature=null：可空项存空串，bean 置 null（各功能用内置默认）")
    void update_nullableNumber() {
        service.update("wenjin.ai.temperature", null, 1L);

        verify(mapper).upsert("wenjin.ai.temperature", "", 0, 1L);
        assertThat(ai.getTemperature()).isNull();
    }

    @Test
    @DisplayName("update secret：库里是能解开的密文（encrypted=1），bean 是明文；返回项不含明文")
    void update_secret_encryptedAndMasked() throws Exception {
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        when(mapper.selectById("wenjin.ai.api-key")).thenReturn(row("wenjin.ai.api-key", "ct", 1));

        AdminConfigItemVO vo = service.update("wenjin.ai.api-key", PLAIN_KEY, 1L);

        verify(mapper).upsert(eq("wenjin.ai.api-key"), stored.capture(), eq(1), eq(1L));
        assertThat(stored.getValue()).doesNotContain(PLAIN_KEY);
        assertThat(crypto.decrypt("wenjin.ai.api-key", stored.getValue())).isEqualTo(PLAIN_KEY);
        assertThat(ai.getApiKey()).isEqualTo(PLAIN_KEY);
        assertThat(vo.getValue()).isNull();
        assertThat(vo.getDefaultValue()).isNull();
        assertThat(vo.getConfigured()).isTrue();
        assertThat(vo.getMasked()).isEqualTo("3210");
        assertThat(JSON.writeValueAsString(vo)).doesNotContain(PLAIN_KEY);
    }

    @Test
    @DisplayName("白名单外 key → 400，不碰库也不碰 bean")
    void update_unknownKey_400() {
        assertThatThrownBy(() -> service.update("wenjin.auth.secret", "x".repeat(40), 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode()));
        assertThatThrownBy(() -> service.update("wenjin.features.eval-enabled", true, 1L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.reset("nope"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("校验越界 / 类型不对 → 400，bean 不变、不写库")
    void update_invalid_400() {
        assertThatThrownBy(() -> service.update("wenjin.limits.companion.max-input-chars", 5000, 1L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update("wenjin.ai.base-url", "not-a-url", 1L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update("wenjin.ai.model", "", 1L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update("wenjin.features.rag-enabled", "maybe", 1L))
                .isInstanceOf(BusinessException.class);

        verify(mapper, never()).upsert(anyString(), anyString(), anyInt(), anyLong());
        assertThat(limits.getCompanion().getMaxInputChars()).isEqualTo(4000);
        assertThat(ai.getBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(ai.getModel()).isEqualTo("deepseek-v4-pro");
    }

    // ───────────────────────── reset ─────────────────────────

    @Test
    @DisplayName("reset：删覆盖行，bean 恢复为启动快照值（含 secret 默认值）")
    void reset_restoresSnapshot() {
        service.snapshotDefaults();
        service.update("wenjin.ai.model", "other-model", 1L);
        service.update("wenjin.ai.api-key", PLAIN_KEY, 1L);
        assertThat(ai.getModel()).isEqualTo("other-model");

        AdminConfigItemVO vo = service.reset("wenjin.ai.model");
        service.reset("wenjin.ai.api-key");

        verify(mapper).deleteById("wenjin.ai.model");
        verify(mapper).deleteById("wenjin.ai.api-key");
        assertThat(ai.getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(ai.getApiKey()).isEqualTo(DEFAULT_KEY);
        assertThat(vo.isOverridden()).isFalse();
        assertThat(vo.getValue()).isEqualTo("deepseek-v4-pro");
        assertThat(vo.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("快照先于应用覆盖：启动应用了库里的覆盖值后，reset 仍回到 yml 默认值")
    void snapshotTakenBeforeOverrides() {
        when(mapper.selectList(any())).thenReturn(List.of(row("wenjin.ai.model", "db-model", 0)));

        service.snapshotDefaults();
        service.applyStoredOverrides();
        assertThat(ai.getModel()).isEqualTo("db-model");

        service.reset("wenjin.ai.model");
        assertThat(ai.getModel()).isEqualTo("deepseek-v4-pro");
    }

    // ───────────────────────── applyStoredOverrides ─────────────────────────

    @Test
    @DisplayName("启动应用：合法项生效（含 secret 解密）；白名单外 / 解密失败 / 值不合规 / 明文 secret 跳过，不抛")
    void applyStoredOverrides_skipsBadRows() {
        ConfigCrypto otherKey = new ConfigCrypto("another-config-secret-at-least-32-bytes", "x",
                new String[]{"test"});
        when(mapper.selectList(any())).thenReturn(List.of(
                row("wenjin.features.rag-enabled", "false", 0),
                row("wenjin.ai.api-key", crypto.encrypt("wenjin.ai.api-key", PLAIN_KEY), 1),
                row("wenjin.ai.vision.api-key", otherKey.encrypt("wenjin.ai.vision.api-key", PLAIN_KEY), 1),
                row("wenjin.removed.key", "1", 0),
                row("wenjin.limits.companion.max-recent-messages", "9999", 0),
                row("wenjin.ai.temperature", "", 0),
                row("wenjin.ai.vision.model", "glm-plain", 1)));
        ai.getVision().setApiKey("vision-env-key-123");

        int applied = service.applyStoredOverrides();

        assertThat(applied).isEqualTo(3);
        assertThat(features.isRagEnabled()).isFalse();
        assertThat(ai.getApiKey()).isEqualTo(PLAIN_KEY);
        assertThat(ai.getTemperature()).isNull();
        assertThat(ai.getVision().getApiKey()).as("解密失败保留默认").isEqualTo("vision-env-key-123");
        assertThat(ai.getVision().getModel()).as("encrypted 标记不符跳过").isEqualTo("glm-5v-turbo");
        assertThat(limits.getCompanion().getMaxRecentMessages()).as("越界跳过").isEqualTo(20);
    }

    @Test
    @DisplayName("读表失败（如未迁移 V18）：返回 0，不抛，bean 保持默认")
    void applyStoredOverrides_tableMissing() {
        when(mapper.selectList(any())).thenThrow(new RuntimeException("Table 'wenjin.sys_config' doesn't exist"));

        assertThat(service.applyStoredOverrides()).isZero();
        assertThat(ai.getModel()).isEqualTo("deepseek-v4-pro");
    }

    // ───────────────────────── helpers ─────────────────────────

    private static SysConfig row(String key, String value, int encrypted) {
        SysConfig r = new SysConfig();
        r.setConfigKey(key);
        r.setConfigValue(value);
        r.setEncrypted(encrypted);
        return r;
    }

    private static AdminConfigItemVO item(List<AdminConfigGroupVO> groups, String key) {
        return groups.stream().flatMap(g -> g.getItems().stream())
                .filter(i -> i.getKey().equals(key)).findFirst().orElseThrow();
    }
}
