package com.wenjin.support.rag;

import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 切块 → 知识点自动初标（确定性、零 LLM）。
 *
 * <p>对每个 chunk 按课程节点名称打分：
 * <ul>
 *   <li>节点名完整命中正文：{@value #W_NAME_BODY}（出现 2、3+ 次各 +{@value #W_NAME_REPEAT}）；</li>
 *   <li>名称切分出的关键词（按「、，与和及/」等分隔，去通用词）命中正文：每词 {@value #W_KEYWORD_BODY}，累计上限 {@value #CAP_KEYWORD_BODY}；</li>
 *   <li>标题路径命中：名称完整 +{@value #W_NAME_PATH}、关键词每词 +{@value #W_KEYWORD_PATH}（上限 {@value #CAP_KEYWORD_PATH}）。</li>
 * </ul>
 * 得分 ≥ {@value #MIN_SCORE} 的节点中取前 {@value #TOP_N}，置信度 = min({@value #MAX_CONFIDENCE}, 得分 / {@value #SCALE})，
 * 恒低于人工映射的 1.000，便于 {@code batch-approve minConfidence} 筛选。无命中的 chunk 不产生标签。
 */
public final class ChunkNodeLabeler {

    public static final double W_NAME_BODY = 10;
    public static final double W_NAME_REPEAT = 1;
    public static final double W_KEYWORD_BODY = 4;
    public static final double CAP_KEYWORD_BODY = 8;
    public static final double W_NAME_PATH = 6;
    public static final double W_KEYWORD_PATH = 2;
    public static final double CAP_KEYWORD_PATH = 4;
    public static final double MIN_SCORE = 4;
    public static final double SCALE = 16;
    public static final double MAX_CONFIDENCE = 0.99;
    public static final int TOP_N = 2;

    private static final Pattern SPLIT = Pattern.compile("[\\s、，,;；/／·()（）\\[\\]【】:：\\-—与和及或]+");
    private static final Pattern PAREN = Pattern.compile("[（(][^）)]*[）)]");
    private static final Pattern ASCII_ONLY = Pattern.compile("^[\\x00-\\x7F]+$");
    private static final Set<String> GENERIC = Set.of(
            "概述", "概念", "基础", "基本", "简介", "原理", "应用", "方法", "技术", "介绍", "总结", "内容",
            "设计", "分析", "管理", "模型", "理论", "实践", "实现", "过程", "问题", "知识", "系统", "基本概念");

    public record NodeCandidate(String code, String name) {}

    public record Label(String nodeCode, BigDecimal confidence, double score) {}

    private record Prepared(String code, String fullName, List<String> keywords) {}

    private final List<Prepared> nodes;

    public ChunkNodeLabeler(List<NodeCandidate> candidates) {
        List<Prepared> list = new ArrayList<>();
        if (candidates != null) {
            for (NodeCandidate c : candidates) {
                if (c == null || !StringUtils.hasText(c.code()) || !StringUtils.hasText(c.name())) {
                    continue;
                }
                String full = normalize(PAREN.matcher(c.name()).replaceAll(""));
                if (full.length() < 2) {
                    full = normalize(c.name());
                }
                if (full.length() < 2) {
                    continue;
                }
                list.add(new Prepared(c.code(), full, keywordsOf(full)));
            }
        }
        this.nodes = List.copyOf(list);
    }

    /** @return 至多 {@link #TOP_N} 个标签，按得分降序（同分按 code 升序）。 */
    public List<Label> label(String content, String headingPath) {
        String body = normalize(content);
        String path = normalize(headingPath);
        if (body.isEmpty() && path.isEmpty()) {
            return List.of();
        }
        List<Label> hits = new ArrayList<>();
        for (Prepared n : nodes) {
            double score = score(n, body, path);
            if (score >= MIN_SCORE) {
                hits.add(new Label(n.code(), confidence(score), score));
            }
        }
        hits.sort(Comparator.comparingDouble(Label::score).reversed().thenComparing(Label::nodeCode));
        return hits.size() <= TOP_N ? hits : List.copyOf(hits.subList(0, TOP_N));
    }

    static BigDecimal confidence(double score) {
        double c = Math.min(MAX_CONFIDENCE, score / SCALE);
        return BigDecimal.valueOf(c).setScale(3, RoundingMode.HALF_UP);
    }

    private static double score(Prepared n, String body, String path) {
        double s = 0;
        int count = countOccurrences(body, n.fullName());
        if (count > 0) {
            s += W_NAME_BODY + W_NAME_REPEAT * (Math.min(count, 3) - 1);
        }
        double kwBody = 0;
        double kwPath = 0;
        for (String kw : n.keywords()) {
            if (body.contains(kw)) {
                kwBody += W_KEYWORD_BODY;
            }
            if (path.contains(kw)) {
                kwPath += W_KEYWORD_PATH;
            }
        }
        s += Math.min(kwBody, CAP_KEYWORD_BODY);
        s += Math.min(kwPath, CAP_KEYWORD_PATH);
        if (path.contains(n.fullName())) {
            s += W_NAME_PATH;
        }
        return s;
    }

    private static List<String> keywordsOf(String full) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : SPLIT.split(full)) {
            if (part.equals(full) || GENERIC.contains(part)) {
                continue;
            }
            int min = ASCII_ONLY.matcher(part).matches() ? 3 : 2;
            if (part.length() >= min) {
                out.add(part);
            }
        }
        return List.copyOf(out);
    }

    private static int countOccurrences(String text, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }
}
