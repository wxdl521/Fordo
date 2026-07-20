package com.wenjin.support;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.CompanionMessage;
import com.wenjin.support.PromptAssembler.AssembleRequest;
import com.wenjin.support.PromptAssembler.AssembledPrompt;
import com.wenjin.support.PromptAssembler.SlotType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PromptAssembler table-driven cases (≥12 groups): budgets, truncation order,
 * RECENT latest preserved, empty slots, SYSTEM fail-fast.
 */
class PromptAssemblerTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("PromptAssembler table-driven")
    void table(Case c) {
        LimitsProperties limits = new LimitsProperties();
        c.configure().accept(limits);
        PromptAssembler assembler = new PromptAssembler(limits);

        if (c.expectIse()) {
            assertThatThrownBy(() -> assembler.assemble(c.request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SYSTEM");
            return;
        }

        AssembledPrompt out = assembler.assemble(c.request());
        c.assertOut().accept(out);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // 1 — empty slots: only optional system when blank request
                args("empty_all_slots_yields_no_messages",
                        limits -> { },
                        new AssembleRequest(null, null, List.of(), List.of(), List.of()),
                        false,
                        out -> {
                            assertThat(out.messages()).isEmpty();
                            assertThat(out.usedChars().get(SlotType.SYSTEM)).isZero();
                            assertThat(out.usedChars().get(SlotType.SUMMARY)).isZero();
                            assertThat(out.usedChars().get(SlotType.MEMORY)).isZero();
                            assertThat(out.usedChars().get(SlotType.RETRIEVAL)).isZero();
                            assertThat(out.usedChars().get(SlotType.RECENT)).isZero();
                            assertThat(out.truncatedSlots()).isEmpty();
                        }),

                // 2 — SYSTEM only
                args("system_only_injected_as_system_role",
                        limits -> { },
                        new AssembleRequest("you are tutor", null, List.of(), List.of(), List.of()),
                        false,
                        out -> {
                            assertThat(out.messages()).containsExactly(
                                    new CompanionAiClient.ChatMsg("system", "you are tutor"));
                            assertThat(out.usedChars().get(SlotType.SYSTEM)).isEqualTo("you are tutor".length());
                            assertThat(out.truncatedSlots()).isEmpty();
                        }),

                // 3 — SYSTEM over own budget → fail-fast
                args("system_over_budget_fail_fast",
                        limits -> limits.getPrompt().setSystemChars(5),
                        new AssembleRequest("too-long-system", null, List.of(), List.of(), List.of()),
                        true,
                        out -> { }),

                // 4 — SUMMARY pseudo dialogue
                args("summary_injected_as_fake_dialogue_pair",
                        limits -> { },
                        new AssembleRequest("sys", "old stuff", List.of(), List.of(), List.of(msg(1, 1, "hi"))),
                        false,
                        out -> {
                            assertThat(out.messages().get(0).role()).isEqualTo("system");
                            assertThat(out.messages().get(1).role()).isEqualTo("user");
                            assertThat(out.messages().get(1).content())
                                    .startsWith(PromptAssembler.SUMMARY_ROLE_PREFIX)
                                    .contains("old stuff");
                            assertThat(out.messages().get(2)).isEqualTo(
                                    new CompanionAiClient.ChatMsg("assistant", PromptAssembler.SUMMARY_ACK));
                            assertThat(out.messages().get(3).content()).isEqualTo("hi");
                            assertThat(out.usedChars().get(SlotType.SUMMARY)).isPositive();
                        }),

                // 5 — MEMORY pseudo dialogue + empty retrieval skill
                args("memory_injected_and_skill_retrieval_empty",
                        limits -> { },
                        new AssembleRequest("sys", null,
                                List.of("remember A", "remember B"), List.of(), List.of(msg(1, 1, "q"))),
                        false,
                        out -> {
                            assertThat(out.messages().stream().map(CompanionAiClient.ChatMsg::content))
                                    .anyMatch(c -> c.contains("【记忆要点】") && c.contains("remember A"));
                            assertThat(out.messages().stream().map(CompanionAiClient.ChatMsg::content))
                                    .anyMatch(c -> c.equals(PromptAssembler.MEMORY_ACK));
                            assertThat(out.usedChars().get(SlotType.SKILL)).isZero();
                            assertThat(out.usedChars().get(SlotType.RETRIEVAL)).isZero();
                            assertThat(out.usedChars().get(SlotType.MEMORY)).isPositive();
                        }),

                // 6 — per-slot SUMMARY tail truncation
                args("summary_slot_budget_keeps_tail",
                        limits -> limits.getPrompt().setSummaryChars(10),
                        new AssembleRequest("s", "ABCDEFGHIJKLMNOP", List.of(), List.of(), List.of()),
                        false,
                        out -> {
                            assertThat(out.truncatedSlots()).contains(SlotType.SUMMARY);
                            String user = out.messages().stream()
                                    .filter(m -> "user".equals(m.role()))
                                    .map(CompanionAiClient.ChatMsg::content)
                                    .findFirst().orElse("");
                            assertThat(user).endsWith("GHIJKLMNOP");
                            assertThat(user).doesNotContain("ABCDEF");
                        }),

                // 7 — MEMORY drop low-intensity (from end) under slot budget
                args("memory_slot_budget_drops_from_end",
                        limits -> limits.getPrompt().setMemoryChars(12),
                        new AssembleRequest("s", null,
                                List.of("aaaa", "bbbb", "cccccccccccc"), List.of(), List.of()),
                        false,
                        out -> {
                            assertThat(out.truncatedSlots()).contains(SlotType.MEMORY);
                            String joined = out.messages().stream()
                                    .filter(m -> "user".equals(m.role()))
                                    .map(CompanionAiClient.ChatMsg::content)
                                    .findFirst().orElse("");
                            // body budget 12: "aaaa\nbbbb" = 9 fits; third line drops from end
                            assertThat(joined).contains("aaaa").contains("bbbb")
                                    .doesNotContain("cccccccccccc");
                        }),

                // 8 — RETRIEVAL whole-tail drop under slot budget
                args("retrieval_slot_budget_drops_whole_tail_blocks",
                        limits -> limits.getPrompt().setRetrievalChars(5),
                        new AssembleRequest("s", null, List.of(),
                                List.of("12345", "67890"), List.of()),
                        false,
                        out -> {
                            assertThat(out.truncatedSlots()).contains(SlotType.RETRIEVAL);
                            List<String> users = out.messages().stream()
                                    .filter(m -> "user".equals(m.role()))
                                    .map(CompanionAiClient.ChatMsg::content)
                                    .toList();
                            assertThat(users).containsExactly("12345");
                        }),

                // 9 — RECENT drop from head under slot budget; latest preserved
                args("recent_slot_budget_drops_from_head_keeps_latest",
                        limits -> limits.getPrompt().setRecentChars(5),
                        new AssembleRequest("s", null, List.of(), List.of(),
                                List.of(msg(1, 2, "aaaa"), msg(2, 1, "bbbb"), msg(3, 1, "LATEST"))),
                        false,
                        out -> {
                            assertThat(out.truncatedSlots()).contains(SlotType.RECENT);
                            assertThat(out.messages().get(out.messages().size() - 1).content())
                                    .isEqualTo("LATEST");
                            // head dropped enough that not all three remain as content
                            long recentMsgs = out.messages().stream()
                                    .filter(m -> "LATEST".equals(m.content())
                                            || "aaaa".equals(m.content())
                                            || "bbbb".equals(m.content()))
                                    .count();
                            assertThat(recentMsgs).isLessThan(3);
                        }),

                // 10 — total budget truncation order: RETRIEVAL before MEMORY
                args("total_budget_truncates_retrieval_before_memory",
                        limits -> {
                            limits.getCompanion().setMaxContextChars(80);
                            limits.getPrompt().setSystemChars(100);
                            limits.getPrompt().setSummaryChars(100);
                            limits.getPrompt().setMemoryChars(100);
                            limits.getPrompt().setRetrievalChars(100);
                            limits.getPrompt().setRecentChars(100);
                        },
                        new AssembleRequest(
                                "SYS",
                                null,
                                List.of("mem-line-one", "mem-line-two"),
                                List.of("RETRIEVAL-BLOCK-AAAA", "RETRIEVAL-BLOCK-BBBB"),
                                List.of(msg(1, 1, "user-latest"))),
                        false,
                        out -> {
                            assertThat(out.truncatedSlots()).contains(SlotType.RETRIEVAL);
                            String all = out.messages().stream()
                                    .map(CompanionAiClient.ChatMsg::content)
                                    .reduce("", String::concat);
                            // retrieval reduced first; memory may still be present if room
                            assertThat(out.messages().get(out.messages().size() - 1).content())
                                    .isEqualTo("user-latest");
                            int total = out.usedChars().values().stream().mapToInt(Integer::intValue).sum();
                            assertThat(total).isLessThanOrEqualTo(80);
                            // at least one retrieval block should be gone under tight total
                            assertThat(all.contains("RETRIEVAL-BLOCK-BBBB")
                                    && all.contains("RETRIEVAL-BLOCK-AAAA")).isFalse();
                        }),

                // 11 — total budget: after retrieval gone, MEMORY drops from end
                args("total_budget_truncates_memory_after_retrieval_empty",
                        limits -> {
                            limits.getCompanion().setMaxContextChars(40);
                            limits.getPrompt().setSystemChars(200);
                            limits.getPrompt().setMemoryChars(200);
                            limits.getPrompt().setRetrievalChars(0);
                            limits.getPrompt().setRecentChars(200);
                        },
                        new AssembleRequest(
                                "S",
                                null,
                                List.of("M1-aaaa", "M2-bbbb", "M3-cccc"),
                                List.of(),
                                List.of(msg(1, 1, "U"))),
                        false,
                        out -> {
                            assertThat(out.truncatedSlots()).contains(SlotType.MEMORY);
                            String all = out.messages().stream()
                                    .map(CompanionAiClient.ChatMsg::content)
                                    .reduce("", String::concat);
                            assertThat(all).doesNotContain("M3-cccc");
                            assertThat(out.messages().get(out.messages().size() - 1).content()).isEqualTo("U");
                            int total = out.usedChars().values().stream().mapToInt(Integer::intValue).sum();
                            assertThat(total).isLessThanOrEqualTo(40);
                        }),

                // 12 — total budget: SUMMARY tail after memory gone
                args("total_budget_truncates_summary_tail_before_recent_head",
                        limits -> {
                            limits.getCompanion().setMaxContextChars(50);
                            limits.getPrompt().setSystemChars(200);
                            limits.getPrompt().setSummaryChars(200);
                            limits.getPrompt().setMemoryChars(0);
                            limits.getPrompt().setRetrievalChars(0);
                            limits.getPrompt().setRecentChars(200);
                        },
                        new AssembleRequest(
                                "S",
                                "SUMMARY_HEAD_XXXXXXXX_SUMMARY_TAIL_YYYYYYYY",
                                List.of(),
                                List.of(),
                                List.of(msg(1, 2, "old"), msg(2, 1, "LATEST"))),
                        false,
                        out -> {
                            assertThat(out.truncatedSlots()).contains(SlotType.SUMMARY);
                            String summaryUser = out.messages().stream()
                                    .filter(m -> m.content() != null && m.content().contains("历史摘要"))
                                    .map(CompanionAiClient.ChatMsg::content)
                                    .findFirst().orElse("");
                            // tail keep under total: retained body ends with TAIL marker
                            assertThat(summaryUser).contains("TAIL");
                            assertThat(out.messages().get(out.messages().size() - 1).content())
                                    .isEqualTo("LATEST");
                            int total = out.usedChars().values().stream().mapToInt(Integer::intValue).sum();
                            assertThat(total).isLessThanOrEqualTo(50);
                        }),

                // 13 — total budget: RECENT drop from head; latest never truncated
                args("total_budget_recent_drops_head_latest_never_truncated",
                        limits -> {
                            limits.getCompanion().setMaxContextChars(20);
                            limits.getPrompt().setSystemChars(200);
                            limits.getPrompt().setSummaryChars(0);
                            limits.getPrompt().setMemoryChars(0);
                            limits.getPrompt().setRetrievalChars(0);
                            limits.getPrompt().setRecentChars(200);
                        },
                        new AssembleRequest(
                                "S", // 1 char
                                null,
                                List.of(),
                                List.of(),
                                List.of(
                                        msg(1, 2, "abcdefghij"),
                                        msg(2, 1, "klmnopqrst"),
                                        msg(3, 1, "LATEST-USER"))),
                        false,
                        out -> {
                            assertThat(out.truncatedSlots()).contains(SlotType.RECENT);
                            CompanionAiClient.ChatMsg last = out.messages().get(out.messages().size() - 1);
                            assertThat(last.content()).isEqualTo("LATEST-USER");
                            assertThat(out.messages().stream().map(CompanionAiClient.ChatMsg::content))
                                    .doesNotContain("abcdefghij");
                        }),

                // 14 — blank memory lines / null recent ignored
                args("blank_memory_and_null_recent_ignored",
                        limits -> { },
                        new AssembleRequest("sys", "  ", List.of("  ", ""), List.of("  "),
                                listWithNull(msg(1, 1, "only"))),
                        false,
                        out -> {
                            assertThat(out.usedChars().get(SlotType.SUMMARY)).isZero();
                            assertThat(out.usedChars().get(SlotType.MEMORY)).isZero();
                            assertThat(out.usedChars().get(SlotType.RETRIEVAL)).isZero();
                            assertThat(out.messages()).containsExactly(
                                    new CompanionAiClient.ChatMsg("system", "sys"),
                                    new CompanionAiClient.ChatMsg("user", "only"));
                        })
        );
    }

    private static List<CompanionMessage> listWithNull(CompanionMessage m) {
        List<CompanionMessage> list = new ArrayList<>();
        list.add(null);
        list.add(m);
        return list;
    }

    private static CompanionMessage msg(long id, int role, String content) {
        CompanionMessage m = new CompanionMessage();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private static Arguments args(String name,
                                  Consumer<LimitsProperties> configure,
                                  AssembleRequest request,
                                  boolean expectIse,
                                  Consumer<AssembledPrompt> assertOut) {
        return Arguments.of(Named.of(name, new Case(configure, request, expectIse, assertOut)));
    }

    record Case(
            Consumer<LimitsProperties> configure,
            AssembleRequest request,
            boolean expectIse,
            Consumer<AssembledPrompt> assertOut
    ) {}
}
