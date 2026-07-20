package com.wenjin.support;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.CompanionMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 伴侣 Prompt 分槽装配：固定槽序 + 分槽预算 + 总预算截断。
 * <p>
 * 槽序：{@code SYSTEM → SKILL → SUMMARY → MEMORY → RETRIEVAL → RECENT}。<br>
 * SYSTEM 与最新用户消息永不截断；SYSTEM 超自身预算视为配置错误（fail-fast）。
 */
@Component
public class PromptAssembler {

    public enum SlotType {
        SYSTEM, SKILL, SUMMARY, MEMORY, RETRIEVAL, RECENT
    }

    public record AssembleRequest(
            String systemPrompt,
            String summaryText,
            List<String> memoryLines,
            List<String> retrievalBlocks,
            List<CompanionMessage> recentMessages
    ) {
        public AssembleRequest {
            memoryLines = copyNonNull(memoryLines);
            retrievalBlocks = copyNonNull(retrievalBlocks);
            recentMessages = copyNonNull(recentMessages);
        }

        private static <T> List<T> copyNonNull(List<T> src) {
            if (src == null || src.isEmpty()) {
                return List.of();
            }
            List<T> out = new ArrayList<>(src.size());
            for (T item : src) {
                if (item != null) {
                    out.add(item);
                }
            }
            return List.copyOf(out);
        }
    }

    public record AssembledPrompt(
            List<CompanionAiClient.ChatMsg> messages,
            Map<SlotType, Integer> usedChars,
            List<SlotType> truncatedSlots
    ) {
        public AssembledPrompt {
            messages = messages == null ? List.of() : List.copyOf(messages);
            usedChars = usedChars == null ? Map.of() : Map.copyOf(usedChars);
            truncatedSlots = truncatedSlots == null ? List.of() : List.copyOf(truncatedSlots);
        }
    }

    /** 与 {@link ConversationContextBuilder#SUMMARY_ROLE_PREFIX} 对齐。 */
    public static final String SUMMARY_ROLE_PREFIX = ConversationContextBuilder.SUMMARY_ROLE_PREFIX;
    public static final String MEMORY_ROLE_PREFIX = "【记忆要点】\n";
    public static final String SUMMARY_ACK = "好的，我已了解之前的对话要点，我们继续。";
    public static final String MEMORY_ACK = "好的，我已记下相关要点，我们继续。";

    private final LimitsProperties limits;

    public PromptAssembler(LimitsProperties limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public AssembledPrompt assemble(AssembleRequest req) {
        Objects.requireNonNull(req, "req");
        LimitsProperties.Prompt budgets = limits.getPrompt() == null
                ? new LimitsProperties.Prompt()
                : limits.getPrompt();
        int maxTotal = Math.max(1, limits.getCompanion().getMaxContextChars());
        int systemBudget = Math.max(0, budgets.getSystemChars());
        int summaryBudget = Math.max(0, budgets.getSummaryChars());
        int memoryBudget = Math.max(0, budgets.getMemoryChars());
        int retrievalBudget = Math.max(0, budgets.getRetrievalChars());
        int recentBudget = Math.max(0, budgets.getRecentChars());

        Set<SlotType> truncated = EnumSet.noneOf(SlotType.class);

        String system = nullToEmpty(req.systemPrompt());
        if (system.length() > systemBudget) {
            throw new IllegalStateException(
                    "SYSTEM prompt exceeds wenjin.limits.prompt.system-chars budget: "
                            + system.length() + " > " + systemBudget
                            + " (config error; SYSTEM is never truncated)");
        }

        // SKILL: empty reserve (no request field yet)
        String skill = "";

        String summary = StringUtils.hasText(req.summaryText()) ? req.summaryText().trim() : "";
        if (summary.length() > summaryBudget) {
            summary = keepTail(summary, summaryBudget);
            truncated.add(SlotType.SUMMARY);
        }

        List<String> memory = new ArrayList<>();
        for (String line : req.memoryLines()) {
            if (StringUtils.hasText(line)) {
                memory.add(line.trim());
            }
        }
        while (!memory.isEmpty() && memoryBodyChars(memory) > memoryBudget) {
            memory.remove(memory.size() - 1);
            truncated.add(SlotType.MEMORY);
        }

        List<String> retrieval = new ArrayList<>();
        for (String block : req.retrievalBlocks()) {
            if (StringUtils.hasText(block)) {
                retrieval.add(block);
            }
        }
        while (!retrieval.isEmpty() && retrievalBodyChars(retrieval) > retrievalBudget) {
            retrieval.remove(retrieval.size() - 1);
            truncated.add(SlotType.RETRIEVAL);
        }

        List<CompanionMessage> recent = new ArrayList<>();
        for (CompanionMessage m : req.recentMessages()) {
            if (m != null) {
                recent.add(m);
            }
        }
        // Per-slot RECENT: drop from head; never drop the latest message
        while (recent.size() > 1 && recentBodyChars(recent) > recentBudget) {
            recent.remove(0);
            truncated.add(SlotType.RECENT);
        }

        // Total budget: RETRIEVAL whole tail → MEMORY end → SUMMARY tail → RECENT head
        while (totalUsed(system, skill, summary, memory, retrieval, recent) > maxTotal) {
            if (!retrieval.isEmpty()) {
                retrieval.remove(retrieval.size() - 1);
                truncated.add(SlotType.RETRIEVAL);
                continue;
            }
            if (!memory.isEmpty()) {
                memory.remove(memory.size() - 1);
                truncated.add(SlotType.MEMORY);
                continue;
            }
            if (StringUtils.hasText(summary)) {
                // Fixed fake-dialogue overhead (prefix + ack); body is what we tail-truncate
                int overhead = SUMMARY_ROLE_PREFIX.length() + SUMMARY_ACK.length();
                int others = totalUsed(system, skill, "", memory, retrieval, recent);
                int roomForBody = Math.max(0, maxTotal - others - overhead);
                String next = keepTail(summary, roomForBody);
                if (next.equals(summary)) {
                    // Cannot shrink further without dropping whole summary
                    if (roomForBody == 0) {
                        summary = "";
                        truncated.add(SlotType.SUMMARY);
                        continue;
                    }
                    break;
                }
                summary = next;
                truncated.add(SlotType.SUMMARY);
                continue;
            }
            if (recent.size() > 1) {
                recent.remove(0);
                truncated.add(SlotType.RECENT);
                continue;
            }
            // Only SYSTEM (+ empty skill) + latest user remain — never truncate those
            break;
        }

        List<CompanionAiClient.ChatMsg> messages = new ArrayList<>();
        if (StringUtils.hasText(system)) {
            messages.add(new CompanionAiClient.ChatMsg("system", system));
        }
        // SKILL reserve: no injection when empty
        if (StringUtils.hasText(summary)) {
            messages.add(new CompanionAiClient.ChatMsg("user", SUMMARY_ROLE_PREFIX + summary));
            messages.add(new CompanionAiClient.ChatMsg("assistant", SUMMARY_ACK));
        }
        if (!memory.isEmpty()) {
            messages.add(new CompanionAiClient.ChatMsg("user", MEMORY_ROLE_PREFIX + String.join("\n", memory)));
            messages.add(new CompanionAiClient.ChatMsg("assistant", MEMORY_ACK));
        }
        for (String block : retrieval) {
            messages.add(new CompanionAiClient.ChatMsg("user", block));
        }
        for (CompanionMessage m : recent) {
            messages.add(new CompanionAiClient.ChatMsg(toRole(m), nullToEmpty(m.getContent())));
        }

        Map<SlotType, Integer> used = new EnumMap<>(SlotType.class);
        used.put(SlotType.SYSTEM, system.length());
        used.put(SlotType.SKILL, skill.length());
        used.put(SlotType.SUMMARY, summarySlotChars(summary));
        used.put(SlotType.MEMORY, memorySlotChars(memory));
        used.put(SlotType.RETRIEVAL, retrievalBodyChars(retrieval));
        used.put(SlotType.RECENT, recentBodyChars(recent));

        // Stable truncation report order matching slot order
        List<SlotType> truncatedList = new ArrayList<>();
        for (SlotType t : SlotType.values()) {
            if (truncated.contains(t)) {
                truncatedList.add(t);
            }
        }
        return new AssembledPrompt(messages, used, truncatedList);
    }

    private static int totalUsed(String system,
                                 String skill,
                                 String summary,
                                 List<String> memory,
                                 List<String> retrieval,
                                 List<CompanionMessage> recent) {
        return system.length()
                + skill.length()
                + summarySlotChars(summary)
                + memorySlotChars(memory)
                + retrievalBodyChars(retrieval)
                + recentBodyChars(recent);
    }

    private static int summarySlotChars(String summary) {
        if (!StringUtils.hasText(summary)) {
            return 0;
        }
        return SUMMARY_ROLE_PREFIX.length() + summary.length() + SUMMARY_ACK.length();
    }

    private static int memorySlotChars(List<String> memory) {
        if (memory == null || memory.isEmpty()) {
            return 0;
        }
        return MEMORY_ROLE_PREFIX.length() + memoryBodyChars(memory) + MEMORY_ACK.length()
                + Math.max(0, memory.size() - 1); // newlines between lines
    }

    private static int memoryBodyChars(List<String> memory) {
        int n = 0;
        for (String line : memory) {
            n += line == null ? 0 : line.length();
        }
        if (memory.size() > 1) {
            n += memory.size() - 1; // '\n' join
        }
        return n;
    }

    private static int retrievalBodyChars(List<String> retrieval) {
        int n = 0;
        for (String block : retrieval) {
            n += block == null ? 0 : block.length();
        }
        return n;
    }

    private static int recentBodyChars(List<CompanionMessage> recent) {
        int n = 0;
        for (CompanionMessage m : recent) {
            n += nullToEmpty(m == null ? null : m.getContent()).length();
        }
        return n;
    }

    private static String keepTail(String text, int maxChars) {
        if (text == null || text.isEmpty() || maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(text.length() - maxChars);
    }

    private static String toRole(CompanionMessage message) {
        return message != null && Objects.equals(message.getRole(), 1) ? "user" : "assistant";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
