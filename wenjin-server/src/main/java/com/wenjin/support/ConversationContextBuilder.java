package com.wenjin.support;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.entity.CompanionMessage;
import com.wenjin.support.PromptAssembler.AssembleRequest;
import com.wenjin.support.PromptAssembler.AssembledPrompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 伴侣对话上下文窗口：长历史压缩为 summary_text + 近窗消息，总字符受预算约束。
 * <p>
 * 规则：近窗默认最新 20 条；更早消息并入滚动摘要；总字符 ≤ maxContextChars；
 * 始终保留最新用户消息。
 * <p>
 * 出站 prompt（legacy / tool messages）经 {@link PromptAssembler} 分槽装配。
 */
@Component
public class ConversationContextBuilder {

    public static final String SUMMARY_ROLE_PREFIX = "【历史摘要】\n";
    public static final String TRUNCATION_REASON_INPUT = "\n\n[输入已截断：超出输入上限]";

    private final LimitsProperties limits;
    private final PromptAssembler promptAssembler;

    /** 单测便捷构造：自建 {@link PromptAssembler}。 */
    public ConversationContextBuilder(LimitsProperties limits) {
        this(limits, new PromptAssembler(limits));
    }

    @Autowired
    public ConversationContextBuilder(LimitsProperties limits, PromptAssembler promptAssembler) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.promptAssembler = Objects.requireNonNull(promptAssembler, "promptAssembler");
    }

    /** 构建结果：近窗消息 + 可选摘要；若摘要需回写则携带更新后的字段。 */
    public record BuiltContext(
            String summaryText,
            Long summaryThroughMessageId,
            List<CompanionMessage> recentMessages,
            boolean summaryUpdated
    ) {
        public BuiltContext {
            recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        }
    }

    /**
     * 根据会话已有摘要 + 历史消息构建模型上下文窗口。
     *
     * @param conversation 会话（含 summary_* 字段）
     * @param history      按创建时间/id 升序的历史（含本轮最新用户消息）；
     *                     可为 watermark 切片（仅 id &gt; summaryThroughMessageId），
     *                     此时已折叠部分以 conversation.summaryText 为准
     */
    public BuiltContext build(CompanionConversation conversation, List<CompanionMessage> history) {
        if (history == null || history.isEmpty()) {
            return new BuiltContext(
                    conversation == null ? null : conversation.getSummaryText(),
                    conversation == null ? null : conversation.getSummaryThroughMessageId(),
                    List.of(),
                    false);
        }

        int maxRecent = Math.max(1, limits.getCompanion().getMaxRecentMessages());
        int maxContext = Math.max(1, limits.getCompanion().getMaxContextChars());
        int maxInput = Math.max(1, limits.getCompanion().getMaxInputChars());

        // 防御：单条过长输入在上下文中截断（接口层通常已拒 4000+）
        List<CompanionMessage> normalized = new ArrayList<>(history.size());
        for (CompanionMessage m : history) {
            normalized.add(clipMessageContent(m, maxInput));
        }

        String existingSummary = conversation == null ? null : conversation.getSummaryText();
        Long existingThrough = conversation == null ? null : conversation.getSummaryThroughMessageId();

        int n = normalized.size();
        if (n <= maxRecent) {
            List<CompanionMessage> recent = fitToBudget(existingSummary, normalized, maxContext);
            return new BuiltContext(existingSummary, existingThrough, recent, false);
        }

        int split = n - maxRecent;
        List<CompanionMessage> older = normalized.subList(0, split);
        List<CompanionMessage> recentRaw = new ArrayList<>(normalized.subList(split, n));

        // 只把尚未纳入摘要的旧消息追加进摘要
        List<CompanionMessage> toFold = new ArrayList<>();
        for (CompanionMessage m : older) {
            if (existingThrough == null || m.getId() == null || m.getId() > existingThrough) {
                toFold.add(m);
            }
        }

        String summary = existingSummary;
        Long throughId = existingThrough;
        boolean updated = false;
        if (!toFold.isEmpty()) {
            summary = mergeSummary(existingSummary, toFold, maxContext / 2);
            CompanionMessage lastFolded = toFold.get(toFold.size() - 1);
            throughId = lastFolded.getId() != null
                    ? lastFolded.getId()
                    : (existingThrough != null ? existingThrough : null);
            // 无 id 时仍用内容摘要，through 保持原值或置为近窗前一条的 id
            if (throughId == null && !older.isEmpty() && older.get(older.size() - 1).getId() != null) {
                throughId = older.get(older.size() - 1).getId();
            }
            updated = true;
        }

        List<CompanionMessage> recent = fitToBudget(summary, recentRaw, maxContext);
        return new BuiltContext(summary, throughId, recent, updated);
    }

    /**
     * Legacy 路径：历史（不含最新用户）+ 最新用户消息。
     */
    public record LegacyPrompt(List<CompanionAiClient.ChatMsg> history, String latestUserMessage) {
        public LegacyPrompt {
            history = history == null ? List.of() : List.copyOf(history);
            latestUserMessage = latestUserMessage == null ? "" : latestUserMessage;
        }
    }

    public LegacyPrompt toLegacyPrompt(BuiltContext ctx) {
        return toLegacyPrompt(ctx, List.of());
    }

    /**
     * Legacy 路径；{@code memoryLines} 非空时经 PromptAssembler MEMORY 槽注入。
     */
    public LegacyPrompt toLegacyPrompt(BuiltContext ctx, List<String> memoryLines) {
        return toLegacyPrompt(ctx, memoryLines, List.of());
    }

    /**
     * Legacy 路径；可选 RETRIEVAL 块注入。
     */
    public LegacyPrompt toLegacyPrompt(BuiltContext ctx,
                                       List<String> memoryLines,
                                       List<String> retrievalBlocks) {
        List<CompanionMessage> recent = ctx.recentMessages();
        if (recent.isEmpty()) {
            return new LegacyPrompt(List.of(), "");
        }
        AssembledPrompt assembled = promptAssembler.assemble(new AssembleRequest(
                null,
                ctx.summaryText(),
                memoryLines == null ? List.of() : memoryLines,
                retrievalBlocks == null ? List.of() : retrievalBlocks,
                recent));
        List<CompanionAiClient.ChatMsg> msgs = assembled.messages();
        if (msgs.isEmpty()) {
            return new LegacyPrompt(List.of(), "");
        }
        CompanionAiClient.ChatMsg latest = msgs.get(msgs.size() - 1);
        List<CompanionAiClient.ChatMsg> history = msgs.size() == 1
                ? List.of()
                : List.copyOf(msgs.subList(0, msgs.size() - 1));
        return new LegacyPrompt(history, nullToEmpty(latest.content()));
    }

    /** Tool 编排路径：system + 可选摘要伪对话 + 近窗消息。 */
    public List<Map<String, Object>> toToolMessages(String systemPrompt, BuiltContext ctx) {
        return toToolMessages(systemPrompt, ctx, List.of());
    }

    /**
     * Tool 编排路径；{@code memoryLines} 非空时注入 MEMORY 伪对话。
     */
    public List<Map<String, Object>> toToolMessages(String systemPrompt,
                                                    BuiltContext ctx,
                                                    List<String> memoryLines) {
        return toToolMessages(systemPrompt, ctx, memoryLines, List.of());
    }

    /**
     * Tool 编排路径；可选 RETRIEVAL 块注入。
     */
    public List<Map<String, Object>> toToolMessages(String systemPrompt,
                                                    BuiltContext ctx,
                                                    List<String> memoryLines,
                                                    List<String> retrievalBlocks) {
        // System is prepended outside AssembleRequest so max-context budgeting still
        // covers summary+recent (historical contract); assembler owns SUMMARY/MEMORY/RECENT form.
        AssembledPrompt assembled = promptAssembler.assemble(new AssembleRequest(
                null,
                ctx.summaryText(),
                memoryLines == null ? List.of() : memoryLines,
                retrievalBlocks == null ? List.of() : retrievalBlocks,
                ctx.recentMessages()));
        List<Map<String, Object>> messages = new ArrayList<>(assembled.messages().size() + 1);
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", systemPrompt == null ? "" : systemPrompt);
        messages.add(system);
        for (CompanionAiClient.ChatMsg m : assembled.messages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", m.role() == null ? "user" : m.role());
            item.put("content", nullToEmpty(m.content()));
            messages.add(item);
        }
        return messages;
    }

    /** 截断 AI 累计输出，超出时附原因；返回是否发生截断。 */
    public boolean appendOutputToken(StringBuilder fullReply, String token, int maxOutputChars) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if (fullReply.length() >= maxOutputChars) {
            return true;
        }
        int room = maxOutputChars - fullReply.length();
        if (token.length() <= room) {
            fullReply.append(token);
            return false;
        }
        fullReply.append(token, 0, room);
        return true;
    }

    public String outputTruncationNotice() {
        return "\n\n[回复已截断：超出输出上限 " + limits.getCompanion().getMaxAiOutputChars() + " 字]";
    }

    public int clampListLimit(Integer limit) {
        int def = Math.max(1, limits.getCompanion().getListDefaultLimit());
        int max = Math.max(def, limits.getCompanion().getListMaxLimit());
        if (limit == null || limit <= 0) {
            return def;
        }
        return Math.min(limit, max);
    }

    // ── internals ──────────────────────────────────────────────────────────

    private static CompanionMessage clipMessageContent(CompanionMessage src, int maxInput) {
        if (src == null) {
            return null;
        }
        String content = src.getContent();
        if (content == null || content.length() <= maxInput) {
            return src;
        }
        CompanionMessage copy = new CompanionMessage();
        copy.setId(src.getId());
        copy.setConversationId(src.getConversationId());
        copy.setRole(src.getRole());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setContent(content.substring(0, maxInput) + TRUNCATION_REASON_INPUT);
        return copy;
    }

    private static String mergeSummary(String existing, List<CompanionMessage> toFold, int maxSummaryChars) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(existing)) {
            sb.append(existing.trim());
        }
        for (CompanionMessage m : toFold) {
            if (m == null) {
                continue;
            }
            String role = Objects.equals(m.getRole(), 1) ? "用户" : "助教";
            String line = role + "：" + nullToEmpty(m.getContent()).replace('\n', ' ').trim();
            if (line.length() > 200) {
                line = line.substring(0, 200) + "…";
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        if (sb.length() > maxSummaryChars) {
            // 保留尾部（更新近的摘要更有用）
            return sb.substring(sb.length() - maxSummaryChars);
        }
        return sb.toString();
    }

    /**
     * 在总预算内装入近窗；始终保留最后一条（最新用户消息）。
     * 必要时从近窗头部丢弃或截断更早内容。
     */
    private static List<CompanionMessage> fitToBudget(String summary,
                                                      List<CompanionMessage> recent,
                                                      int maxContext) {
        if (recent == null || recent.isEmpty()) {
            return List.of();
        }
        int summaryChars = StringUtils.hasText(summary) ? summary.length() : 0;
        int budget = Math.max(0, maxContext - summaryChars);

        CompanionMessage latest = recent.get(recent.size() - 1);
        String latestContent = nullToEmpty(latest.getContent());
        // 最新用户消息优先：预算不足时也硬保它（可截断内容）
        if (latestContent.length() > budget) {
            CompanionMessage clipped = copyWithContent(latest, latestContent.substring(0, Math.max(0, budget)));
            return List.of(clipped);
        }

        List<CompanionMessage> kept = new ArrayList<>();
        int used = latestContent.length();
        for (int i = recent.size() - 2; i >= 0; i--) {
            CompanionMessage m = recent.get(i);
            int len = nullToEmpty(m.getContent()).length();
            if (used + len > budget) {
                break;
            }
            kept.add(0, m);
            used += len;
        }
        kept.add(latest);
        return kept;
    }

    private static CompanionMessage copyWithContent(CompanionMessage src, String content) {
        CompanionMessage copy = new CompanionMessage();
        copy.setId(src.getId());
        copy.setConversationId(src.getConversationId());
        copy.setRole(src.getRole());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setContent(content);
        return copy;
    }

    private static String toRole(CompanionMessage message) {
        return message != null && Objects.equals(message.getRole(), 1) ? "user" : "assistant";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
