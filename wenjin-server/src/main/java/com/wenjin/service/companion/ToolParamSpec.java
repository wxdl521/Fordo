package com.wenjin.service.companion;

import java.util.List;

/**
 * Declarative tool-parameter contract validated by {@link ToolGovernor} before execute.
 *
 * @param name       argument field name
 * @param type       expected JSON type
 * @param required   whether the field must be present and non-null
 * @param maxLen     max string length (STRING only); null = no limit
 * @param min        min numeric value (INT only); null = no limit
 * @param max        max numeric value (INT only); null = no limit
 * @param enumValues allowed string values; null/empty = any
 * @param pattern    regex the string must match; null/blank = any
 */
public record ToolParamSpec(
        String name,
        ParamType type,
        boolean required,
        Integer maxLen,
        Double min,
        Double max,
        List<String> enumValues,
        String pattern
) {
    public enum ParamType {
        STRING,
        INT,
        BOOL,
        ARRAY
    }

    public ToolParamSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("param name is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("param type is required");
        }
        if (enumValues != null) {
            enumValues = List.copyOf(enumValues);
        }
    }

    public static ToolParamSpec string(String name, boolean required) {
        return new ToolParamSpec(name, ParamType.STRING, required, null, null, null, null, null);
    }

    public static ToolParamSpec string(String name, boolean required, Integer maxLen, String pattern) {
        return new ToolParamSpec(name, ParamType.STRING, required, maxLen, null, null, null, pattern);
    }

    public static ToolParamSpec integer(String name, boolean required, Double min, Double max) {
        return new ToolParamSpec(name, ParamType.INT, required, null, min, max, null, null);
    }

    public static ToolParamSpec bool(String name, boolean required) {
        return new ToolParamSpec(name, ParamType.BOOL, required, null, null, null, null, null);
    }

    public static ToolParamSpec array(String name, boolean required) {
        return new ToolParamSpec(name, ParamType.ARRAY, required, null, null, null, null, null);
    }

    /** Common knowledge-node code pattern (e.g. KT12). */
    public static final String NODE_CODE_PATTERN = "^[A-Za-z][A-Za-z0-9_-]{0,31}$";
}
