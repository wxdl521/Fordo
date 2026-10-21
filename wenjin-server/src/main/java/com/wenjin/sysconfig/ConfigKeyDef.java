package com.wenjin.sysconfig;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

/**
 * 一个可编辑配置项的登记：类型、校验范围、中文名、分组、说明，以及如何从 properties bean 读当前值 / 写回新值。
 *
 * <p>值在内存里一律是"类型化"的：bool→{@link Boolean}，整数→{@link Integer}，小数→{@link Double}，
 * string/secret/enum→{@link String}；{@code nullable} 的数字项可为 null。</p>
 *
 * @param integer   number：true=整数
 * @param min       number：下限（含）
 * @param max       number：上限（含）
 * @param nullable  number：允许 null（=各调用方的内置默认）
 * @param maxLength string/secret：最大长度
 * @param urlOnly   string：必须是 http/https URL
 * @param options   enum：可选值
 * @param reader    读 bean 当前值
 * @param applier   写回 bean
 */
public record ConfigKeyDef(
        String key,
        ConfigType type,
        String group,
        String label,
        String description,
        boolean integer,
        Number min,
        Number max,
        boolean nullable,
        int maxLength,
        boolean urlOnly,
        List<String> options,
        Function<ConfigBeans, Object> reader,
        BiConsumer<ConfigBeans, Object> applier) {

    /** secret 明文最短长度：太短的"密钥"既不像真 key，回显末 4 位也会泄露过多。 */
    public static final int SECRET_MIN_LENGTH = 8;

    public ConfigKeyDef {
        options = options == null ? List.of() : List.copyOf(options);
    }

    static ConfigKeyDef bool(String key, String group, String label, String description,
                             Function<ConfigBeans, Boolean> get, BiConsumer<ConfigBeans, Boolean> set) {
        return new ConfigKeyDef(key, ConfigType.BOOL, group, label, description,
                false, null, null, false, 0, false, null,
                get::apply, (b, v) -> set.accept(b, (Boolean) v));
    }

    static ConfigKeyDef integer(String key, String group, String label, String description, int min, int max,
                                ToIntFunction<ConfigBeans> get, ObjIntConsumer<ConfigBeans> set) {
        return new ConfigKeyDef(key, ConfigType.NUMBER, group, label, description,
                true, min, max, false, 0, false, null,
                get::applyAsInt, (b, v) -> set.accept(b, ((Number) v).intValue()));
    }

    static ConfigKeyDef decimal(String key, String group, String label, String description,
                                double min, double max, boolean nullable,
                                Function<ConfigBeans, Double> get, BiConsumer<ConfigBeans, Double> set) {
        return new ConfigKeyDef(key, ConfigType.NUMBER, group, label, description,
                false, min, max, nullable, 0, false, null,
                get::apply, (b, v) -> set.accept(b, v == null ? null : ((Number) v).doubleValue()));
    }

    static ConfigKeyDef text(String key, String group, String label, String description,
                             int maxLength, boolean urlOnly,
                             Function<ConfigBeans, String> get, BiConsumer<ConfigBeans, String> set) {
        return new ConfigKeyDef(key, ConfigType.STRING, group, label, description,
                false, null, null, false, maxLength, urlOnly, null,
                get::apply, (b, v) -> set.accept(b, (String) v));
    }

    static ConfigKeyDef secret(String key, String group, String label, String description, int maxLength,
                               Function<ConfigBeans, String> get, BiConsumer<ConfigBeans, String> set) {
        return new ConfigKeyDef(key, ConfigType.SECRET, group, label, description,
                false, null, null, false, maxLength, false, null,
                get::apply, (b, v) -> set.accept(b, (String) v));
    }

    static ConfigKeyDef choice(String key, String group, String label, String description, List<String> options,
                               Function<ConfigBeans, String> get, BiConsumer<ConfigBeans, String> set) {
        return new ConfigKeyDef(key, ConfigType.ENUM, group, label, description,
                false, null, null, false, 0, false, options,
                get::apply, (b, v) -> set.accept(b, (String) v));
    }

    public boolean isSecret() {
        return type == ConfigType.SECRET;
    }

    /** 读 bean 当前值（secret 为明文，只在服务端内存里用，绝不出接口）。 */
    public Object read(ConfigBeans beans) {
        return reader.apply(beans);
    }

    /** 写回 bean（值须已通过 {@link ConfigValues#parse} 校验）。 */
    public void apply(ConfigBeans beans, Object value) {
        applier.accept(beans, value);
    }
}
