package com.wenjin.support;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则型练习意图检测：从用户自然语言解析是否要启动 start_practice。
 * 纯函数，无 Spring / IO 依赖。
 */
public final class PracticeIntentDetector {

    private static final int DEFAULT_SIZE = 2;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 3;
    private static final int MIN_NAME_LEN = 2;

    /** 字面练习 cue 词组。 */
    private static final Pattern CUE_LITERAL = Pattern.compile(
            "出题|练习|练一下|练一练|刷题|小测|测验|考我|测我|出一道|出两道|出三道|出几道|两道题|几道题|做几道|做两道");

    /** 复盘/错因类请求：即使含「练习」等字样也不是要新出题（交给模型调 get_recent_mistakes）。 */
    private static final Pattern NEGATIVE_CUE = Pattern.compile("复盘|错题|为什么错|做错|错在");

    /** 祈使/意愿式的「练习」：视为明确要题（untargeted 兜底用）。 */
    private static final Pattern REQUEST_PRACTICE = Pattern.compile(
            "(想|要|帮我|给我|带我|陪我|开始|来|做)(点|些|一些|一下|几套|一套)?练习|练习(一下|吧|几套|一套)");
    /** 讲解/答案类提问（untargeted 兜底时用于排除「给我讲讲这道题」）。 */
    private static final Pattern EXPLAIN_CUE = Pattern.compile("讲|解释|答案|思路|为什么|怎么");
    /** 其余单独出现的「练习」视为名词提及（「我练习了很多次」）。 */
    private static final Pattern BARE_PRACTICE_NOUN = Pattern.compile("练习");

    private static final Pattern CUE_OUT_N =Pattern.compile("出\\s*\\d+\\s*道");
    private static final Pattern CUE_GIVE_ME = Pattern.compile("给我.{0,12}题");
    private static final Pattern CUE_LAI = Pattern.compile("来.{0,8}题");
    private static final Pattern CUE_ZUO = Pattern.compile("做.{0,8}题");

    private static final Pattern SIZE_PATTERN =
            Pattern.compile("([一二两三四五六七八九十\\d]+)\\s*道");

    private PracticeIntentDetector() {}

    /**
     * @param userText 最新用户消息
     * @param nodes 课程节点列表（需 name；nodeCode 可选）；可为 null/empty
     * @param focusNodeCode 会话焦点，可 null
     * @param focusNodeName 会话焦点名，可 null
     * @return Optional empty 表示不强制练习
     */
    public static Optional<PracticeIntent> detect(
            String userText,
            List<NodeRef> nodes,
            String focusNodeCode,
            String focusNodeName) {
        if (userText == null || userText.isBlank()) {
            return Optional.empty();
        }
        if (NEGATIVE_CUE.matcher(userText).find() || !hasPracticeCue(userText)) {
            return Optional.empty();
        }

        int size = resolveSize(userText);
        Optional<NodeRef> matched = matchNode(userText, nodes);
        if (matched.isPresent()) {
            NodeRef n = matched.get();
            return Optional.of(new PracticeIntent(n.nodeCode(), n.name(), size));
        }

        boolean hasFocusCode = focusNodeCode != null && !focusNodeCode.isBlank();
        boolean hasFocusName = focusNodeName != null && !focusNodeName.isBlank();
        if (hasFocusCode || hasFocusName) {
            return Optional.of(new PracticeIntent(
                    hasFocusCode ? focusNodeCode : null,
                    hasFocusName ? focusNodeName : null,
                    size));
        }
        return Optional.empty();
    }

    /**
     * 无节点（文本未点名、会话无焦点）时的明确要题请求：返回题量，由调用方兜底选目标节点
     * （到期复习 &gt; 最弱 &gt; 焦点 &gt; 首个节点）。比 {@link #detect} 更严：单独出现的名词「练习」
     * （如「我练习了很多次」）不算请求，须是祈使/意愿式（「练习一下」「想练习」「出题」「考我」等）。
     *
     * @return 题量；不是明确要题 / 复盘错题类请求时为 empty
     */
    public static OptionalInt untargetedRequestSize(String userText) {
        if (userText == null || userText.isBlank() || NEGATIVE_CUE.matcher(userText).find()) {
            return OptionalInt.empty();
        }
        String withoutNounPractice = BARE_PRACTICE_NOUN.matcher(
                REQUEST_PRACTICE.matcher(userText).replaceAll("出题")).replaceAll("");
        if (!hasPracticeCue(withoutNounPractice)) {
            return OptionalInt.empty();
        }
        // 「给我讲讲这道题」之类：只有宽泛的「给我/来/做…题」而在问讲解时不算要题
        if (EXPLAIN_CUE.matcher(userText).find() && !CUE_LITERAL.matcher(withoutNounPractice).find()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(resolveSize(userText));
    }

    private static boolean hasPracticeCue(String text) {
        return CUE_LITERAL.matcher(text).find()
                || CUE_OUT_N.matcher(text).find()
                || CUE_GIVE_ME.matcher(text).find()
                || CUE_LAI.matcher(text).find()
                || CUE_ZUO.matcher(text).find();
    }

    private static int resolveSize(String text) {
        Matcher m = SIZE_PATTERN.matcher(text);
        if (!m.find()) {
            return DEFAULT_SIZE;
        }
        return mapSizeToken(m.group(1));
    }

    private static int mapSizeToken(String token) {
        if (token == null || token.isBlank()) {
            return DEFAULT_SIZE;
        }
        if (token.chars().allMatch(Character::isDigit)) {
            try {
                return clamp(Integer.parseInt(token));
            } catch (NumberFormatException e) {
                return DEFAULT_SIZE;
            }
        }
        // 中文：按字符累计（一/二/两/三…）；四及以上 clamp 到 3
        int value = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            Integer mapped = mapChineseDigit(c);
            if (mapped == null) {
                continue;
            }
            if (c == '十') {
                value = value == 0 ? 10 : value * 10;
            } else {
                value = value == 0 ? mapped : value + mapped;
            }
        }
        if (value <= 0) {
            return DEFAULT_SIZE;
        }
        return clamp(value);
    }

    private static Integer mapChineseDigit(char c) {
        return switch (c) {
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            case '十' -> 10;
            default -> null;
        };
    }

    private static int clamp(int n) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, n));
    }

    /**
     * 最长 name 子串优先（name length≥2）；同长取在文本中先出现；再试 nodeCode 子串。
     */
    private static Optional<NodeRef> matchNode(String userText, List<NodeRef> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }

        NodeRef bestName = null;
        int bestLen = -1;
        int bestIndex = Integer.MAX_VALUE;
        for (NodeRef node : nodes) {
            if (node == null || node.name() == null) {
                continue;
            }
            String name = node.name();
            if (name.length() < MIN_NAME_LEN) {
                continue;
            }
            int idx = userText.indexOf(name);
            if (idx < 0) {
                continue;
            }
            if (name.length() > bestLen
                    || (name.length() == bestLen && idx < bestIndex)) {
                bestName = node;
                bestLen = name.length();
                bestIndex = idx;
            }
        }
        if (bestName != null) {
            return Optional.of(bestName);
        }

        for (NodeRef node : nodes) {
            if (node == null || node.nodeCode() == null || node.nodeCode().isBlank()) {
                continue;
            }
            if (userText.contains(node.nodeCode())) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    public record NodeRef(String nodeCode, String name) {}
}
