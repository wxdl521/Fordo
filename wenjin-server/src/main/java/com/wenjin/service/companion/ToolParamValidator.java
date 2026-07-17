package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Table-driven validation of {@link ToolParamSpec} against tool-call JSON args.
 * Returns a human-readable error message, or {@code null} when valid.
 */
public final class ToolParamValidator {

    private ToolParamValidator() {
    }

    public static String validate(List<ToolParamSpec> specs, JsonNode args) {
        if (specs == null || specs.isEmpty()) {
            return null;
        }
        if (args == null || args.isNull() || !args.isObject()) {
            return "Tool arguments must be a JSON object";
        }
        for (ToolParamSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            String error = validateOne(spec, args.get(spec.name()));
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    private static String validateOne(ToolParamSpec spec, JsonNode value) {
        boolean missing = value == null || value.isNull();
        if (missing) {
            return spec.required() ? "Missing required tool argument: " + spec.name() : null;
        }

        return switch (spec.type()) {
            case STRING -> validateString(spec, value);
            case INT -> validateInt(spec, value);
            case BOOL -> value.isBoolean()
                    ? null
                    : "Tool argument has invalid type: " + spec.name();
            case ARRAY -> value.isArray()
                    ? null
                    : "Tool argument has invalid type: " + spec.name();
        };
    }

    private static String validateString(ToolParamSpec spec, JsonNode value) {
        if (!value.isTextual()) {
            return "Tool argument has invalid type: " + spec.name();
        }
        String text = value.asText();
        if (spec.maxLen() != null && text.length() > spec.maxLen()) {
            return "Tool argument exceeds max length: " + spec.name();
        }
        if (spec.enumValues() != null && !spec.enumValues().isEmpty()
                && !spec.enumValues().contains(text)) {
            return "Tool argument has invalid value: " + spec.name();
        }
        if (spec.pattern() != null && !spec.pattern().isBlank()) {
            try {
                if (!Pattern.compile(spec.pattern()).matcher(text).matches()) {
                    return "Tool argument has invalid format: " + spec.name();
                }
            } catch (PatternSyntaxException e) {
                // Misconfigured pattern should not block tools; treat as pass.
                return null;
            }
        }
        return null;
    }

    private static String validateInt(ToolParamSpec spec, JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            return "Tool argument has invalid type: " + spec.name();
        }
        double number = value.asDouble();
        if (spec.min() != null && number < spec.min()) {
            return "Tool argument below minimum: " + spec.name();
        }
        if (spec.max() != null && number > spec.max()) {
            return "Tool argument above maximum: " + spec.name();
        }
        return null;
    }
}
