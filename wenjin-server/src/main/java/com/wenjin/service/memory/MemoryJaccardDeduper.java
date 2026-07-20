package com.wenjin.service.memory;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆内容 Jaccard 同义去重器。
 * <p>将 content 切为英文词 + 中日韩单字 token 集合，计算二值 Jaccard：
 * {@code |A∩B| / |A∪B|}。默认阈值 0.6（≥ 视为同义）。
 */
@Component
public class MemoryJaccardDeduper {

    /** 英文/数字词 */
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z0-9_]+");

    /** CJK 统一汉字（含扩展 A 基本区） */
    private static final Pattern CJK_CHAR = Pattern.compile("[\\u4E00-\\u9FFF\\u3400-\\u4DBF]");

    public static final double DEFAULT_THRESHOLD = 0.6;

    /**
     * 二值集合 Jaccard 相似度；任一方 token 为空时返回 0。
     */
    public double jaccard(String a, String b) {
        Set<String> sa = tokenize(a);
        Set<String> sb = tokenize(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        for (String t : sa) {
            if (sb.contains(t)) {
                intersection++;
            }
        }
        int union = sa.size() + sb.size() - intersection;
        if (union <= 0) {
            return 0.0;
        }
        return (double) intersection / (double) union;
    }

    /** ≥ threshold 视为同义（含边界）。 */
    public boolean isSynonym(String a, String b, double threshold) {
        return jaccard(a, b) >= threshold;
    }

    public boolean isSynonym(String a, String b) {
        return isSynonym(a, b, DEFAULT_THRESHOLD);
    }

    /**
     * 中英混合分词：拉丁词小写；每个 CJK 字符独立成 token。
     */
    public Set<String> tokenize(String content) {
        if (!StringUtils.hasText(content)) {
            return Collections.emptySet();
        }
        Set<String> tokens = new HashSet<>();
        Matcher latin = LATIN_WORD.matcher(content);
        while (latin.find()) {
            tokens.add(latin.group().toLowerCase(Locale.ROOT));
        }
        Matcher cjk = CJK_CHAR.matcher(content);
        while (cjk.find()) {
            tokens.add(cjk.group());
        }
        return tokens;
    }
}
