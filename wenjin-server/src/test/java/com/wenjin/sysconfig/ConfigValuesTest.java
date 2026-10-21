package com.wenjin.sysconfig;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ConfigValues：各类型校验 / 规范化 / 存储编码 / 掩码；报错不回显提交值。
 */
class ConfigValuesTest {

    private static final ConfigKeyDef BOOL = ConfigKeyDef.bool("k.bool", "g", "开关", "", b -> true, (b, v) -> { });
    private static final ConfigKeyDef INT = ConfigKeyDef.integer("k.int", "g", "条数", "", 1, 100,
            b -> 1, (b, v) -> { });
    private static final ConfigKeyDef DEC = ConfigKeyDef.decimal("k.dec", "g", "温度", "", 0, 2, true,
            b -> null, (b, v) -> { });
    private static final ConfigKeyDef DEC_REQUIRED = ConfigKeyDef.decimal("k.dec2", "g", "系数", "", 0, 1, false,
            b -> 0.5, (b, v) -> { });
    private static final ConfigKeyDef TEXT = ConfigKeyDef.text("k.text", "g", "模型名", "", 8, false,
            b -> "m", (b, v) -> { });
    private static final ConfigKeyDef URL = ConfigKeyDef.text("k.url", "g", "服务基址", "", 255, true,
            b -> "http://x", (b, v) -> { });
    private static final ConfigKeyDef SECRET = ConfigKeyDef.secret("k.secret", "g", "API Key", "", 64,
            b -> null, (b, v) -> { });
    private static final ConfigKeyDef CHOICE = ConfigKeyDef.choice("k.enum", "g", "模式", "", List.of("a", "b"),
            b -> "a", (b, v) -> { });

    @Test
    @DisplayName("bool：接受 JSON 布尔与 \"true\"/\"false\" 字符串（库里存的是字符串）；其它 → 400")
    void bool() {
        assertThat(ConfigValues.parse(BOOL, true)).isEqualTo(true);
        assertThat(ConfigValues.parse(BOOL, " FALSE ")).isEqualTo(false);
        for (Object bad : new Object[]{null, 1, "yes", ""}) {
            assertBad(BOOL, bad);
        }
    }

    @Test
    @DisplayName("整数：范围含端点；小数 / 非数字 / 越界 / null → 400；\"20\" 与 20.0 规范化为 Integer")
    void integer() {
        assertThat(ConfigValues.parse(INT, 1)).isEqualTo(1);
        assertThat(ConfigValues.parse(INT, 100)).isEqualTo(100);
        assertThat(ConfigValues.parse(INT, "20")).isEqualTo(20);
        assertThat(ConfigValues.parse(INT, 20.0)).isEqualTo(20);
        for (Object bad : new Object[]{0, 101, 1.5, "abc", "", null, true, Double.NaN}) {
            assertBad(INT, bad);
        }
    }

    @Test
    @DisplayName("小数：范围 0–2，可空项 null / 空串 → null；非可空项空值 → 400")
    void decimal() {
        assertThat(ConfigValues.parse(DEC, 0.4)).isEqualTo(0.4);
        assertThat(ConfigValues.parse(DEC, 2)).isEqualTo(2.0);
        assertThat(ConfigValues.parse(DEC, "1.25")).isEqualTo(1.25);
        assertThat(ConfigValues.parse(DEC, null)).isNull();
        assertThat(ConfigValues.parse(DEC, " ")).isNull();
        assertBad(DEC, 2.01);
        assertBad(DEC, -0.1);
        assertBad(DEC_REQUIRED, null);
    }

    @Test
    @DisplayName("string：去首尾空白；空串（恢复默认走 DELETE）/ 超长 / 含空白控制字符 / 非字符串 → 400")
    void text() {
        assertThat(ConfigValues.parse(TEXT, "  gpt-4o ")).isEqualTo("gpt-4o");
        for (Object bad : new Object[]{"", "   ", "123456789", "a b", "a\tb", 12, null}) {
            assertBad(TEXT, bad);
        }
    }

    @Test
    @DisplayName("URL：只认 http/https 且有主机，不带查询串 / 片段 / 用户信息")
    void url() {
        assertThat(ConfigValues.parse(URL, "https://api.deepseek.com")).isEqualTo("https://api.deepseek.com");
        assertThat(ConfigValues.parse(URL, "http://127.0.0.1:8000/v4/")).isEqualTo("http://127.0.0.1:8000/v4/");
        for (Object bad : new Object[]{"ftp://x.com", "api.deepseek.com", "https://", "https://x.com?a=1",
                "https://x.com#f", "https://u:p@x.com", "javascript:alert(1)"}) {
            assertBad(URL, bad);
        }
    }

    @Test
    @DisplayName("secret：长度 8–上限、无空白；报错 message 绝不回显提交的值")
    void secret() {
        assertThat(ConfigValues.parse(SECRET, " sk-abcdefgh ")).isEqualTo("sk-abcdefgh");
        String leaked = "sk-has space-secret";
        assertThatThrownBy(() -> ConfigValues.parse(SECRET, leaked))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("sk-has").doesNotContain("space-"));
        assertBad(SECRET, "short");
        assertBad(SECRET, "x".repeat(65));
        assertBad(SECRET, null);
    }

    @Test
    @DisplayName("enum：只接受登记的选项")
    void choice() {
        assertThat(ConfigValues.parse(CHOICE, "b")).isEqualTo("b");
        assertBad(CHOICE, "c");
    }

    @Test
    @DisplayName("存储编码：bool/int 原样、小数去尾零、可空 null 存空串；secret 不许走明文编码")
    void serialize() {
        assertThat(ConfigValues.serialize(BOOL, false)).isEqualTo("false");
        assertThat(ConfigValues.serialize(INT, 4000)).isEqualTo("4000");
        assertThat(ConfigValues.serialize(DEC, 0.40)).isEqualTo("0.4");
        assertThat(ConfigValues.serialize(DEC, 1.0)).isEqualTo("1");
        assertThat(ConfigValues.serialize(DEC, null)).isEmpty();
        assertThat(ConfigValues.serialize(TEXT, "m")).isEqualTo("m");
        // 往返：编码后能被 parse 读回同值
        assertThat(ConfigValues.parse(DEC, ConfigValues.serialize(DEC, 0.4))).isEqualTo(0.4);
        assertThat(ConfigValues.parse(DEC, ConfigValues.serialize(DEC, null))).isNull();
        assertThatThrownBy(() -> ConfigValues.serialize(SECRET, "sk-abcdefgh"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("掩码：只给末 4 位；未配置或不足 8 位 → null（不回显）")
    void mask() {
        assertThat(ConfigValues.mask(null)).isNull();
        assertThat(ConfigValues.mask("  ")).isNull();
        assertThat(ConfigValues.mask("abc1234")).isNull();
        assertThat(ConfigValues.mask(" sk-1234567890abcd ")).isEqualTo("abcd");
    }

    private static void assertBad(ConfigKeyDef def, Object raw) {
        assertThatThrownBy(() -> ConfigValues.parse(def, raw))
                .as("%s 应拒绝 %s", def.key(), raw)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode()));
    }
}
