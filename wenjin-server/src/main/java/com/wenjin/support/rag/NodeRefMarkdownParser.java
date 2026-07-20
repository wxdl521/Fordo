package com.wenjin.support.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NODE_REF Markdown 契约解析：YAML frontmatter + 每个 {@code ##} 一节。
 * <p>纯函数；失败抛 {@link NodeRefParseException} 并携带明细。
 */
public final class NodeRefMarkdownParser {

    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\r?\\n(.*?)\\r?\\n---\\r?\\n?(.*)$", Pattern.DOTALL);
    private static final Pattern H2 = Pattern.compile("(?m)^##\\s+(.+?)\\s*$");
    private static final Pattern NODE_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,31}$");

    private NodeRefMarkdownParser() {}

    /**
     * 解析 NODE_REF 文档。
     *
     * @param markdown         全文
     * @param allowedNodeCodes 课程节点白名单；null/空表示不校验白名单（仍校验格式）
     */
    public static ParsedNodeRef parse(String markdown, Set<String> allowedNodeCodes) {
        if (!StringUtils.hasText(markdown)) {
            throw new NodeRefParseException(List.of("document is empty"));
        }
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').trim();
        Matcher fm = FRONTMATTER.matcher(normalized);
        if (!fm.matches()) {
            throw new NodeRefParseException(List.of("missing or invalid YAML frontmatter (--- ... ---)"));
        }
        Map<String, String> meta = parseFrontmatterBody(fm.group(1));
        List<String> errors = new ArrayList<>();
        String nodeCode = require(meta, "node_code", errors);
        String title = require(meta, "title", errors);
        String sourceRef = require(meta, "source_ref", errors);
        if (nodeCode != null && !NODE_CODE.matcher(nodeCode).matches()) {
            errors.add("node_code has invalid format: " + nodeCode);
        }
        if (nodeCode != null && allowedNodeCodes != null && !allowedNodeCodes.isEmpty()
                && !allowedNodeCodes.contains(nodeCode)) {
            errors.add("node_code not in course whitelist: " + nodeCode);
        }
        String body = fm.group(2) == null ? "" : fm.group(2).trim();
        List<Section> sections = splitSections(body, errors);
        if (!errors.isEmpty()) {
            throw new NodeRefParseException(errors);
        }
        return new ParsedNodeRef(nodeCode, title, sourceRef, Map.copyOf(meta), sections);
    }

    private static String require(Map<String, String> meta, String key, List<String> errors) {
        String value = meta.get(key);
        if (!StringUtils.hasText(value)) {
            errors.add("frontmatter missing required field: " + key);
            return null;
        }
        return value.trim();
    }

    private static Map<String, String> parseFrontmatterBody(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return map;
        }
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(colon + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            map.put(key, value);
        }
        return map;
    }

    private static List<Section> splitSections(String body, List<String> errors) {
        if (!StringUtils.hasText(body)) {
            errors.add("document body is empty after frontmatter");
            return List.of();
        }
        Matcher matcher = H2.matcher(body);
        List<int[]> headings = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            headings.add(new int[]{matcher.start(), matcher.end()});
            titles.add(matcher.group(1).trim());
        }
        if (headings.isEmpty()) {
            errors.add("no ## section headings found");
            return List.of();
        }
        // leading prose before first ## is rejected (contract: body starts with ##)
        String leading = body.substring(0, headings.get(0)[0]).trim();
        if (StringUtils.hasText(leading)) {
            errors.add("content before first ## heading is not allowed");
        }
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            int contentStart = headings.get(i)[1];
            int contentEnd = (i + 1 < headings.size()) ? headings.get(i + 1)[0] : body.length();
            String content = body.substring(contentStart, contentEnd).trim();
            if (!StringUtils.hasText(content)) {
                errors.add("section is empty: ## " + titles.get(i));
                continue;
            }
            sections.add(new Section(titles.get(i), content));
        }
        if (sections.isEmpty() && errors.stream().noneMatch(e -> e.startsWith("section is empty"))) {
            errors.add("no non-empty ## sections");
        }
        return sections;
    }

    public record ParsedNodeRef(
            String nodeCode,
            String title,
            String sourceRef,
            Map<String, String> frontmatter,
            List<Section> sections
    ) {
        public ParsedNodeRef {
            Objects.requireNonNull(nodeCode, "nodeCode");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(sourceRef, "sourceRef");
            frontmatter = frontmatter == null ? Map.of() : Map.copyOf(frontmatter);
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    public record Section(String heading, String content) {
        public Section {
            Objects.requireNonNull(heading, "heading");
            Objects.requireNonNull(content, "content");
        }
    }

    public static final class NodeRefParseException extends RuntimeException {
        private final List<String> details;

        public NodeRefParseException(List<String> details) {
            super(details == null || details.isEmpty()
                    ? "NODE_REF parse failed"
                    : "NODE_REF parse failed: " + String.join("; ", details));
            this.details = details == null ? List.of() : List.copyOf(details);
        }

        public List<String> details() {
            return details;
        }
    }
}
