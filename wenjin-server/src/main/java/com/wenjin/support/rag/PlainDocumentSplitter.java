package com.wenjin.support.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 非 NODE_REF 文档（md/txt/pdf/docx 抽取文本）→ 带标题路径的切块。
 *
 * <p>标题识别：Markdown {@code #..####}、中文「第N章/节」、「一、」、数字编号「1.2 标题」（短行且无句末标点）。
 * 小节过小时与后续小节合并（至 {@link TextChunker#TARGET}），大节交给 {@link TextChunker}。纯函数。
 */
public final class PlainDocumentSplitter {

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,4})\\s+(.+?)\\s*#*$");
    private static final Pattern CN_CHAPTER = Pattern.compile("^第[一二三四五六七八九十百零\\d]+[章节篇部分]\\s*.{0,40}$");
    private static final Pattern CN_ENUM = Pattern.compile("^[一二三四五六七八九十]+、\\s*.{1,40}$");
    private static final Pattern NUM_HEADING = Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,2}){0,3})[.、\\s]\\s*(\\S.{0,40})$");
    private static final Pattern SENTENCE_PUNCT = Pattern.compile("[。！？；;，,]$");
    private static final int MAX_PATH = 200;

    private PlainDocumentSplitter() {}

    public record Piece(String content, String path, String heading) {}

    private record Section(String path, String heading, String body) {}

    public static List<Piece> split(String text, String docTitle) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String title = StringUtils.hasText(docTitle) ? docTitle.trim() : "";
        List<Section> sections = sections(text.replace("\r\n", "\n").replace('\r', '\n'), title);
        List<Section> merged = mergeSmall(sections);
        List<Piece> out = new ArrayList<>();
        for (Section s : merged) {
            String path = clip(s.path());
            for (TextChunker.Chunk c : TextChunker.chunk(s.body(), path)) {
                out.add(new Piece(c.content(), path, s.heading()));
            }
        }
        return out;
    }

    private static List<Section> sections(String text, String title) {
        List<Section> out = new ArrayList<>();
        String[] stack = new String[5];
        String heading = "";
        String path = title;
        StringBuilder body = new StringBuilder();
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            int level = headingLevel(line);
            if (level > 0) {
                flush(out, path, heading, body);
                String name = headingText(line);
                stack[level] = name;
                for (int i = level + 1; i < stack.length; i++) {
                    stack[i] = null;
                }
                heading = name;
                path = joinPath(title, stack);
            } else {
                body.append(raw).append('\n');
            }
        }
        flush(out, path, heading, body);
        return out;
    }

    private static void flush(List<Section> out, String path, String heading, StringBuilder body) {
        String b = body.toString().trim();
        body.setLength(0);
        if (!b.isEmpty()) {
            out.add(new Section(path, heading, b));
        }
    }

    private static List<Section> mergeSmall(List<Section> sections) {
        List<Section> out = new ArrayList<>();
        Section cur = null;
        for (Section s : sections) {
            if (cur == null) {
                cur = s;
                continue;
            }
            if (cur.body().length() < TextChunker.MIN
                    && cur.body().length() + s.heading().length() + s.body().length() + 2 <= TextChunker.TARGET) {
                String add = s.heading().isEmpty() ? s.body() : s.heading() + "\n" + s.body();
                cur = new Section(cur.path(), cur.heading(), cur.body() + "\n" + add);
            } else {
                out.add(cur);
                cur = s;
            }
        }
        if (cur != null) {
            out.add(cur);
        }
        return out;
    }

    /** @return 0 非标题；1-4 层级 */
    static int headingLevel(String line) {
        if (line.isEmpty() || line.length() > 60) {
            return 0;
        }
        Matcher md = MD_HEADING.matcher(line);
        if (md.matches()) {
            return md.group(1).length();
        }
        if (SENTENCE_PUNCT.matcher(line).find()) {
            return 0;
        }
        if (CN_CHAPTER.matcher(line).matches()) {
            return line.contains("节") ? 2 : 1;
        }
        if (CN_ENUM.matcher(line).matches()) {
            return 2;
        }
        Matcher num = NUM_HEADING.matcher(line);
        if (num.matches()) {
            String rest = num.group(2);
            if (rest.codePoints().anyMatch(Character::isLetter)) {
                return Math.min(4, num.group(1).split("\\.").length);
            }
        }
        return 0;
    }

    private static String headingText(String line) {
        Matcher md = MD_HEADING.matcher(line);
        return md.matches() ? md.group(2).trim() : line;
    }

    private static String joinPath(String title, String[] stack) {
        List<String> parts = new ArrayList<>();
        if (!title.isEmpty()) {
            parts.add(title);
        }
        for (String s : stack) {
            if (s != null) {
                parts.add(s);
            }
        }
        return String.join(">", parts);
    }

    private static String clip(String path) {
        if (path == null) {
            return "";
        }
        return path.length() <= MAX_PATH ? path : path.substring(0, MAX_PATH);
    }
}
