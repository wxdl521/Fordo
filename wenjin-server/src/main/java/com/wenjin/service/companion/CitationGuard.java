package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.service.trace.StepRecord;
import com.wenjin.support.AgentStepStatus;
import com.wenjin.support.AgentStepType;
import com.wenjin.support.rag.CitationValidator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单个 turn 的引用守卫：可引用集合 = RETRIEVAL 注入的 chunkId ∪ 本 turn {@code search_materials} 返回的 chunkId。
 *
 * <p>turn 结束时从最终文本提取 {@code 资料 #N}，经 {@link CitationValidator} 校验；无效编号在
 * <b>持久化文本</b>中替换为 {@link #INVALID_MARK}（编号一并移除，避免历史消息里出现点不开的芯片）。
 * 非线程安全，每个 turn new 一个；工具并行执行时 {@link #addToolResult} 仅在编排线程回放结果阶段调用。
 */
public final class CitationGuard {

    public static final String STEP_NAME = "citation_check";
    /** 无效引用在持久化文本中的替换文案（前端 {@code neutralizeInvalidCitations} 与之保持一致）。 */
    public static final String INVALID_MARK = "（引用无效）";

    private static final Pattern REF = Pattern.compile("资料\\s*#(\\d+)");
    /** 截断 JSON 时的兜底提取（要求数字后跟 , 或 }，避免把被截断的半个数字当成编号）。 */
    private static final Pattern CHUNK_ID = Pattern.compile("\"chunkId\"\\s*:\\s*(\\d+)\\s*[,}]");
    /** 溢出 long 的畸形编号在 step 的 invalid 数组里的占位（使 citationsValid 判失败）。 */
    public static final long OVERFLOW_ID = -1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<Long> citable = new LinkedHashSet<>();

    /** @param injectedChunkIds 本 turn RETRIEVAL 注入的 chunkId（可空） */
    public CitationGuard(Collection<Long> injectedChunkIds) {
        if (injectedChunkIds != null) {
            for (Long id : injectedChunkIds) {
                if (id != null) {
                    citable.add(id);
                }
            }
        }
    }

    public Set<Long> citable() {
        return Set.copyOf(citable);
    }

    /** 收集 {@code search_materials} 结果 {@code items[].chunkId}；其他工具 / 非法内容忽略。 */
    public void addToolResult(String toolName, String content) {
        if (!SearchMaterialsTool.NAME.equals(toolName) || content == null || content.isBlank()) {
            return;
        }
        try {
            JsonNode items = MAPPER.readTree(content).path("items");
            if (!items.isArray()) {
                return;
            }
            for (JsonNode item : items) {
                JsonNode id = item.path("chunkId");
                if (id.isIntegralNumber()) {
                    citable.add(id.asLong());
                }
            }
        } catch (Exception e) {
            // 可能被截断：按正则兜底收集 chunkId；纯非 JSON 文本无匹配，不贡献可引用集合
            Matcher m = CHUNK_ID.matcher(content);
            while (m.find()) {
                Long id = parseId(m.group(1));
                if (id != null) {
                    citable.add(id);
                }
            }
        }
    }

    /** 文本中 {@code 资料 #N} 的编号（按出现顺序、去重；超出 long 的忽略）。 */
    public static List<Long> extractIds(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        Matcher m = REF.matcher(text);
        while (m.find()) {
            Long id = parseId(m.group(1));
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /** 校验并改写：无效编号 → {@link #INVALID_MARK}。 */
    public Verdict check(String text) {
        if (text == null || text.isEmpty()) {
            return new Verdict(List.of(), List.of(), text, false, false);
        }
        List<Long> cited = extractIds(text);
        CitationValidator.Result result = CitationValidator.validate(citable, cited);
        String rewritten = neutralize(text);
        boolean hadOverflow = hasOverflowRef(text);
        boolean hasCitations = !cited.isEmpty() || hadOverflow;
        return new Verdict(result.keptChunkIds(), result.strippedChunkIds(), rewritten,
                hasCitations, hadOverflow);
    }

    /** 仅改写（不产出 verdict），供中断 / 取消 / 断开路径的持久化使用。 */
    public String neutralize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = REF.matcher(text);
        StringBuilder sb = null;
        int last = 0;
        while (m.find()) {
            Long id = parseId(m.group(1));
            if (id != null && citable.contains(id)) {
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(text.length());
            }
            sb.append(text, last, m.start()).append(INVALID_MARK);
            last = m.end();
        }
        if (sb == null) {
            return text;
        }
        return sb.append(text, last, text.length()).toString();
    }

    public StepRecord toStep(Verdict verdict, int durationMs) {
        List<Long> invalid = new ArrayList<>(verdict.invalid());
        if (verdict.overflow()) {
            invalid.add(OVERFLOW_ID);
        }
        List<Long> cited = new ArrayList<>(verdict.valid());
        cited.addAll(invalid);
        String input = "{\"cited\":" + idsJson(cited) + ",\"citable\":" + idsJson(citable) + "}";
        String output = "{\"valid\":" + idsJson(verdict.valid()) + ",\"invalid\":" + idsJson(invalid) + "}";
        return new StepRecord(AgentStepType.CITATION, STEP_NAME, AgentStepStatus.OK, input, output,
                0, 0, Math.max(0, durationMs), null);
    }

    private static boolean hasOverflowRef(String text) {
        Matcher m = REF.matcher(text);
        while (m.find()) {
            if (parseId(m.group(1)) == null) {
                return true;
            }
        }
        return false;
    }

    private static Long parseId(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String idsJson(Collection<Long> ids) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Long id : ids) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(id);
        }
        return sb.append(']').toString();
    }

    /**
     * @param valid       最终文本里命中可引用集合的编号
     * @param invalid     越界编号（溢出 long 的畸形编号不在此列，但文本已改写）
     * @param text        改写后的文本（无无效引用时与入参为同一对象）
     * @param hasCitations 文本里出现过任何 {@code 资料 #N}
     */
    public record Verdict(List<Long> valid, List<Long> invalid, String text, boolean hasCitations, boolean overflow) {
        public Verdict {
            valid = valid == null ? List.of() : List.copyOf(valid);
            invalid = invalid == null ? List.of() : List.copyOf(invalid);
        }

        /** 文本是否被改写过。 */
        public boolean rewritten() {
            return !invalid.isEmpty() || overflow;
        }
    }
}
