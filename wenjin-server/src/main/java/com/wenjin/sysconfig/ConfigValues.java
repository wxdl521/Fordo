package com.wenjin.sysconfig;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Locale;

/**
 * 配置值的校验 / 规范化 / 存储编码。
 *
 * <p>同一套 {@link #parse} 既校验接口传入值，也校验启动时从库里读出的值（库里存的是字符串），
 * 所以 bool / number 同时接受 JSON 原生类型和字符串。</p>
 *
 * <p>报错文案只含配置项中文名与规则，<b>绝不回显提交的值</b>（secret 尤其如此；GlobalExceptionHandler 会把
 * 业务异常 message 写进日志）。</p>
 */
public final class ConfigValues {

    private ConfigValues() {}

    /**
     * 把原始值校验并规范化为类型化值。
     *
     * @param raw 接口传入（Boolean / Number / String / null）或库里读出的字符串
     * @return Boolean / Integer / Double / String；nullable 数字项可为 null
     * @throws BusinessException 400，不合规
     */
    public static Object parse(ConfigKeyDef def, Object raw) {
        return switch (def.type()) {
            case BOOL -> parseBool(def, raw);
            case NUMBER -> parseNumber(def, raw);
            case STRING -> parseText(def, raw);
            case SECRET -> parseSecret(def, raw);
            case ENUM -> parseChoice(def, raw);
        };
    }

    /** 非 secret 值的库内编码；nullable 数字的 null 存空串。 */
    public static String serialize(ConfigKeyDef def, Object value) {
        if (def.isSecret()) {
            throw new IllegalArgumentException("secret 必须经 ConfigCrypto 加密后存储");
        }
        if (value == null) {
            return "";
        }
        if (value instanceof Double d) {
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    /**
     * 接口用的掩码：只给末 4 位（前端显示为 {@code ••••abcd}）。未配置或不足
     * {@value ConfigKeyDef#SECRET_MIN_LENGTH} 位（末 4 位占比过高）→ null，只凭 configured 显示「已配置」。
     */
    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String s = secret.trim();
        if (s.length() < ConfigKeyDef.SECRET_MIN_LENGTH) {
            return null;
        }
        return s.substring(s.length() - 4);
    }

    // ───────────────────────── 各类型 ─────────────────────────

    private static Boolean parseBool(ConfigKeyDef def, Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(t)) {
                return Boolean.TRUE;
            }
            if ("false".equals(t)) {
                return Boolean.FALSE;
            }
        }
        throw bad(def, "须为 true 或 false");
    }

    private static Object parseNumber(ConfigKeyDef def, Object raw) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            if (def.nullable()) {
                return null;
            }
            throw bad(def, "不能为空");
        }
        BigDecimal n;
        try {
            if (raw instanceof Number || raw instanceof String) {
                n = new BigDecimal(raw.toString().trim());
            } else {
                throw bad(def, "须为数字");
            }
        } catch (NumberFormatException e) {
            throw bad(def, "须为数字");
        }
        if (def.integer() && n.stripTrailingZeros().scale() > 0) {
            throw bad(def, "须为整数");
        }
        BigDecimal min = new BigDecimal(def.min().toString());
        BigDecimal max = new BigDecimal(def.max().toString());
        if (n.compareTo(min) < 0 || n.compareTo(max) > 0) {
            throw bad(def, "须在 " + plain(def.min()) + " 到 " + plain(def.max()) + " 之间");
        }
        return def.integer() ? (Object) n.intValueExact() : (Object) n.doubleValue();
    }

    private static String parseText(ConfigKeyDef def, Object raw) {
        String s = requireString(def, raw);
        if (s.isEmpty()) {
            throw bad(def, "不能为空（要恢复默认值请用「恢复默认」）");
        }
        if (s.length() > def.maxLength()) {
            throw bad(def, "长度不能超过 " + def.maxLength());
        }
        if (hasWhitespaceOrControl(s)) {
            throw bad(def, "不能包含空白或控制字符");
        }
        if (def.urlOnly() && !isHttpUrl(s)) {
            throw bad(def, "须为 http:// 或 https:// 开头的地址（不带查询参数）");
        }
        return s;
    }

    private static String parseSecret(ConfigKeyDef def, Object raw) {
        String s = requireString(def, raw);
        if (s.length() < ConfigKeyDef.SECRET_MIN_LENGTH || s.length() > def.maxLength()) {
            throw bad(def, "长度须在 " + ConfigKeyDef.SECRET_MIN_LENGTH + " 到 " + def.maxLength() + " 之间");
        }
        if (hasWhitespaceOrControl(s)) {
            throw bad(def, "不能包含空白或控制字符");
        }
        return s;
    }

    private static String parseChoice(ConfigKeyDef def, Object raw) {
        String s = requireString(def, raw);
        if (!def.options().contains(s)) {
            throw bad(def, "取值须为 " + String.join(" / ", def.options()) + " 之一");
        }
        return s;
    }

    // ───────────────────────── helpers ─────────────────────────

    private static String requireString(ConfigKeyDef def, Object raw) {
        if (raw == null) {
            throw bad(def, "不能为空");
        }
        if (!(raw instanceof String s)) {
            throw bad(def, "须为字符串");
        }
        return s.trim();
    }

    private static boolean hasWhitespaceOrControl(String s) {
        return s.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    private static boolean isHttpUrl(String s) {
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme();
            return scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getRawUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String plain(Number n) {
        return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
    }

    private static BusinessException bad(ConfigKeyDef def, String rule) {
        return new BusinessException(ResultCode.BAD_REQUEST, def.label() + "（" + def.key() + "）" + rule);
    }
}
