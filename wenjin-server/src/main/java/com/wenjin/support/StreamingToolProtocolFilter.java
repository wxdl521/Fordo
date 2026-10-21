package com.wenjin.support;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式工具伪协议净化器：在 token 流中即时剔除模型泄漏的 DSML / tool_calls / invoke / parameter 伪协议块。
 *
 * <p>有状态、非线程安全、纯逻辑。只在缓冲尾部"可能是协议起始"时暂扣；确认是协议则整块丢弃到闭合标签
 * （未闭合则丢到流尾），确认不是则立即放行。不做任何空白折叠 / trim（已下发文本无法撤回）；
 * 持久化文本仍应使用整段 {@link CompanionFinalTextSanitizer}。
 */
public final class StreamingToolProtocolFilter {

    /** 未决前缀的最大暂扣长度，超过即放行。 */
    static final int MAX_HOLD = 32;
    /** 块内为识别跨 delta 的闭合标签而保留的尾部长度。 */
    private static final int BLOCK_TAIL = 96;

    private static final String[] TARGETS = {"tool_calls", "invoke", "parameter", "dsml"};

    private enum Decision { RELEASE, WAIT, PROTOCOL }

    private final StringBuilder pending = new StringBuilder();
    private boolean inBlock;
    private Pattern closePattern;

    /** 接收一段增量，返回现在可安全下发的文本。 */
    public String accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        pending.append(delta);
        StringBuilder out = new StringBuilder();
        drain(out, false);
        return out.toString();
    }

    /** 流结束：放行未决的非协议前缀；未闭合的协议块丢弃。 */
    public String flush() {
        StringBuilder out = new StringBuilder();
        drain(out, true);
        return out.toString();
    }

    private void drain(StringBuilder out, boolean last) {
        while (true) {
            if (inBlock) {
                Matcher m = closePattern.matcher(pending);
                if (m.find()) {
                    pending.delete(0, m.end());
                    inBlock = false;
                    closePattern = null;
                    continue;
                }
                if (last) {
                    pending.setLength(0);
                } else if (pending.length() > BLOCK_TAIL) {
                    pending.delete(0, pending.length() - BLOCK_TAIL);
                }
                return;
            }
            int lt = pending.indexOf("<");
            if (lt < 0) {
                out.append(pending);
                pending.setLength(0);
                return;
            }
            out.append(pending, 0, lt);
            pending.delete(0, lt);

            Decision d = decide(pending);
            if (d == Decision.RELEASE) {
                out.append('<');
                pending.deleteCharAt(0);
                continue;
            }
            if (d == Decision.WAIT) {
                if (last) {
                    out.append(pending);
                    pending.setLength(0);
                }
                return;
            }
            // PROTOCOL：需要完整开标签
            int gt = tagEnd(pending);
            if (gt < 0) {
                if (last) {
                    pending.setLength(0);
                }
                return;
            }
            String tag = pending.substring(0, gt + 1);
            pending.delete(0, gt + 1);
            String name = tagName(tag);
            boolean closing = tag.startsWith("</");
            boolean selfClosing = tag.endsWith("/>");
            if (!closing && !selfClosing && isBlockName(name)) {
                inBlock = true;
                closePattern = Pattern.compile(
                        "</[\\s|｜]*(?:dsml[\\s|｜]*)?" + Pattern.quote(name) + "[^>＞]*[>＞]",
                        Pattern.CASE_INSENSITIVE);
            }
        }
    }

    private static boolean isBlockName(String name) {
        return name.equals("tool_calls") || name.equals("invoke") || name.equals("parameter");
    }

    private static int tagEnd(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '>' || c == '＞') {
                return i;
            }
        }
        return -1;
    }

    /** 提取标签规范名：忽略 / 竖线 / 空白 / DSML 前缀后的小写名字（含下划线）。 */
    private static String tagName(String tag) {
        int i = 1;
        int n = tag.length();
        if (i < n && tag.charAt(i) == '/') {
            i++;
        }
        i = skipSeparators(tag, i);
        if (tag.regionMatches(true, i, "dsml", 0, 4)) {
            i = skipSeparators(tag, i + 4);
        }
        int start = i;
        while (i < n && (Character.isLetter(tag.charAt(i)) && tag.charAt(i) < 128 || tag.charAt(i) == '_'
                || (i > start && Character.isDigit(tag.charAt(i))))) {
            i++;
        }
        return tag.substring(start, i).toLowerCase(Locale.ROOT);
    }

    private static int skipSeparators(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '|' || c == '｜') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /** pending 以 '<' 开头，判断其是否为协议起始。 */
    private static Decision decide(CharSequence rest) {
        int len = rest.length();
        int j = 1;
        if (j < len && rest.charAt(j) == '/') {
            j++;
        }
        if (j < len && (rest.charAt(j) == '|' || rest.charAt(j) == '｜')) {
            return Decision.PROTOCOL;
        }
        while (j < len && Character.isWhitespace(rest.charAt(j))) {
            j++;
        }
        if (j >= len) {
            return wait(len);
        }
        int k = j;
        while (k < len) {
            char c = rest.charAt(k);
            if ((c < 128 && Character.isLetter(c)) || c == '_') {
                k++;
            } else {
                break;
            }
        }
        boolean ended = k < len;
        String w = rest.subSequence(j, k).toString().toLowerCase(Locale.ROOT);
        for (String t : TARGETS) {
            if (w.length() < t.length()) {
                if (!ended && t.startsWith(w)) {
                    return wait(len);
                }
            } else if (w.equals(t)) {
                if (!ended) {
                    return wait(len);
                }
                char next = rest.charAt(k);
                if (Character.isWhitespace(next) || next == '>' || next == '＞' || next == '/'
                        || next == '|' || next == '｜') {
                    return Decision.PROTOCOL;
                }
            }
        }
        return Decision.RELEASE;
    }

    private static Decision wait(int len) {
        return len > MAX_HOLD ? Decision.RELEASE : Decision.WAIT;
    }
}
