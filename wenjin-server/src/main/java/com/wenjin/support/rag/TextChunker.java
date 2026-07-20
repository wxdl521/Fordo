package com.wenjin.support.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用文本切块：目标 {@value #TARGET} 字、区间 [{@value #MIN}, {@value #MAX}]、overlap≈{@value #OVERLAP}。
 * <p>切分优先级：{@code ##/###} 标题 &gt; 空行 &gt; 。！？ &gt; 硬切。
 * 纯函数，可单测。
 */
public final class TextChunker {

    public static final int TARGET = 500;
    public static final int MIN = 300;
    public static final int MAX = 800;
    public static final int OVERLAP = 100;

    private static final Pattern HEADING = Pattern.compile("(?m)^(#{2,3})\\s+.+$");
    private static final Pattern BLANK = Pattern.compile("\\n[ \\t]*\\n+");
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？]");

    private TextChunker() {}

    public static List<Chunk> chunk(String text) {
        return chunk(text, null);
    }

    /**
     * @param pathPrefix 写入 meta 的标题路径前缀（可空）
     */
    public static List<Chunk> chunk(String text, String pathPrefix) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.length() <= MAX) {
            return List.of(new Chunk(normalized, pathPrefix));
        }
        List<String> units = splitToUnits(normalized);
        return pack(units, pathPrefix);
    }

    /**
     * 递归切到每段 ≤ {@link #MAX}：标题 → 空行 → 句号 → 硬切。
     */
    static List<String> splitToUnits(String text) {
        if (text.length() <= MAX) {
            return List.of(text);
        }
        List<String> byHeading = splitBeforeMatches(text, HEADING);
        if (byHeading.size() > 1) {
            return expandUnits(byHeading);
        }
        List<String> byBlank = splitOnBlank(text);
        if (byBlank.size() > 1) {
            return expandUnits(byBlank);
        }
        List<String> bySentence = splitAfterSentences(text);
        if (bySentence.size() > 1) {
            return expandUnits(bySentence);
        }
        return hardCut(text);
    }

    private static List<String> expandUnits(List<String> parts) {
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.length() <= MAX) {
                out.add(t);
            } else {
                out.addAll(splitToUnits(t));
            }
        }
        return out;
    }

    private static List<String> splitBeforeMatches(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        List<Integer> cuts = new ArrayList<>();
        while (m.find()) {
            int at = m.start();
            if (at > 0 && at < text.length()) {
                cuts.add(at);
            }
        }
        if (cuts.isEmpty()) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        int prev = 0;
        for (int cut : cuts) {
            if (cut <= prev) {
                continue;
            }
            parts.add(text.substring(prev, cut));
            prev = cut;
        }
        if (prev < text.length()) {
            parts.add(text.substring(prev));
        }
        return parts;
    }

    private static List<String> splitOnBlank(String text) {
        String[] raw = BLANK.split(text);
        if (raw.length <= 1) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        for (String r : raw) {
            if (StringUtils.hasText(r)) {
                parts.add(r.trim());
            }
        }
        return parts.size() <= 1 ? List.of(text) : parts;
    }

    private static List<String> splitAfterSentences(String text) {
        Matcher m = SENTENCE_END.matcher(text);
        List<Integer> ends = new ArrayList<>();
        while (m.find()) {
            ends.add(m.end());
        }
        if (ends.isEmpty()) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        int prev = 0;
        for (int end : ends) {
            if (end <= prev) {
                continue;
            }
            String piece = text.substring(prev, end).trim();
            if (StringUtils.hasText(piece)) {
                parts.add(piece);
            }
            prev = end;
        }
        if (prev < text.length()) {
            String tail = text.substring(prev).trim();
            if (StringUtils.hasText(tail)) {
                parts.add(tail);
            }
        }
        return parts.size() <= 1 ? List.of(text) : parts;
    }

    static List<String> hardCut(String text) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + MAX);
            parts.add(text.substring(i, end));
            if (end >= text.length()) {
                break;
            }
            i = end;
        }
        return parts;
    }

    /**
     * 将 ≤MAX 的小段打包：优先接近 TARGET，不超过 MAX；块间保留 overlap 尾缀。
     */
    static List<Chunk> pack(List<String> units, String pathPrefix) {
        if (units == null || units.isEmpty()) {
            return List.of();
        }
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String unit : units) {
            if (!StringUtils.hasText(unit)) {
                continue;
            }
            String u = unit.trim();
            if (buf.isEmpty()) {
                buf.append(u);
                continue;
            }
            int joinedLen = buf.length() + 1 + u.length();
            if (joinedLen <= MAX && buf.length() < TARGET) {
                buf.append('\n').append(u);
                continue;
            }
            if (joinedLen <= MAX && buf.length() >= MIN) {
                // already past MIN; only grow if still under TARGET
                if (buf.length() < TARGET) {
                    buf.append('\n').append(u);
                    continue;
                }
            }
            if (joinedLen <= MAX && buf.length() < MIN) {
                buf.append('\n').append(u);
                continue;
            }
            // flush
            flushBuffer(chunks, buf, pathPrefix);
            String overlap = takeOverlap(chunks.get(chunks.size() - 1).content());
            buf.setLength(0);
            if (StringUtils.hasText(overlap)) {
                buf.append(overlap);
                if (!overlap.endsWith("\n")) {
                    buf.append('\n');
                }
            }
            buf.append(u);
            if (buf.length() > MAX) {
                // drop overlap and keep unit alone (unit is ≤ MAX by construction)
                buf.setLength(0);
                buf.append(u);
            }
        }
        if (!buf.isEmpty()) {
            String last = buf.toString();
            if (last.length() < MIN && !chunks.isEmpty()) {
                Chunk prev = chunks.remove(chunks.size() - 1);
                String merged = prev.content() + "\n" + last;
                if (merged.length() <= MAX + OVERLAP) {
                    // may slightly exceed MAX when merging tiny tail; hard re-chunk if needed
                    if (merged.length() <= MAX) {
                        chunks.add(new Chunk(merged, pathPrefix));
                    } else {
                        chunks.add(prev);
                        chunks.add(new Chunk(last, pathPrefix));
                    }
                } else {
                    chunks.add(prev);
                    chunks.add(new Chunk(last, pathPrefix));
                }
            } else {
                chunks.add(new Chunk(last, pathPrefix));
            }
        }
        return List.copyOf(chunks);
    }

    private static void flushBuffer(List<Chunk> chunks, StringBuilder buf, String pathPrefix) {
        if (buf.isEmpty()) {
            return;
        }
        chunks.add(new Chunk(buf.toString(), pathPrefix));
    }

    private static String takeOverlap(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= OVERLAP) {
            return text;
        }
        return text.substring(text.length() - OVERLAP);
    }

    public record Chunk(String content, String path) {
        public Chunk {
            Objects.requireNonNull(content, "content");
        }

        public int charCount() {
            return content.length();
        }
    }
}
