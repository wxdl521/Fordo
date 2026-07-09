package com.wenjin.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PracticeIntentDetector 纯函数单测：练习意图关键词、题量、最长节点名、焦点回退。
 */
class PracticeIntentDetectorTest {

    private static final PracticeIntentDetector.NodeRef SE_OVERVIEW =
            new PracticeIntentDetector.NodeRef("KT01", "软件工程概述");
    private static final PracticeIntentDetector.NodeRef SE_SHORT =
            new PracticeIntentDetector.NodeRef("KT00", "软件工程");

    @Test
    @DisplayName("给我出两道关于软件工程概述的题 → size=2, nodeName 命中")
    void detectsPracticeWithNodeNameAndSizeTwo() {
        Optional<PracticeIntent> intent = PracticeIntentDetector.detect(
                "给我出两道关于软件工程概述的题",
                List.of(SE_OVERVIEW),
                null,
                null);

        assertThat(intent).isPresent();
        assertThat(intent.get().size()).isEqualTo(2);
        assertThat(intent.get().nodeName()).isEqualTo("软件工程概述");
        assertThat(intent.get().nodeCode()).isEqualTo("KT01");
    }

    @Test
    @DisplayName("纯问答无练习 cue → empty")
    void pureQuestionWithoutCueIsEmpty() {
        Optional<PracticeIntent> intent = PracticeIntentDetector.detect(
                "软件工程概述是什么？",
                List.of(SE_OVERVIEW),
                null,
                null);

        assertThat(intent).isEmpty();
    }

    @Test
    @DisplayName("练一下 + 仅 focus → 使用焦点节点")
    void practiceCueWithFocusOnlyUsesFocus() {
        Optional<PracticeIntent> intent = PracticeIntentDetector.detect(
                "练一下",
                List.of(),
                "KT99",
                "需求工程");

        assertThat(intent).isPresent();
        assertThat(intent.get().nodeCode()).isEqualTo("KT99");
        assertThat(intent.get().nodeName()).isEqualTo("需求工程");
        assertThat(intent.get().size()).isEqualTo(2);
    }

    @Test
    @DisplayName("考我 + 无节点无 focus → empty")
    void cueWithoutNodeOrFocusIsEmpty() {
        Optional<PracticeIntent> intent = PracticeIntentDetector.detect(
                "考我",
                null,
                null,
                null);

        assertThat(intent).isEmpty();
    }

    @Test
    @DisplayName("三道 → size=3；五道 → clamp 到 3")
    void sizeThreeAndFiveClamp() {
        Optional<PracticeIntent> three = PracticeIntentDetector.detect(
                "出三道软件工程概述的题",
                List.of(SE_OVERVIEW),
                null,
                null);
        assertThat(three).isPresent();
        assertThat(three.get().size()).isEqualTo(3);

        // 「出五道」不在字面 cue 列表（仅一/二/三/几）；用「考我」+「五道」测 size clamp
        Optional<PracticeIntent> five = PracticeIntentDetector.detect(
                "考我五道软件工程概述",
                List.of(SE_OVERVIEW),
                null,
                null);
        assertThat(five).isPresent();
        assertThat(five.get().size()).isEqualTo(3);
    }

    @Test
    @DisplayName("长短节点名并存时选更长 name")
    void prefersLongestNodeNameMatch() {
        Optional<PracticeIntent> intent = PracticeIntentDetector.detect(
                "给我出两道软件工程概述的题",
                List.of(SE_SHORT, SE_OVERVIEW),
                null,
                null);

        assertThat(intent).isPresent();
        assertThat(intent.get().nodeName()).isEqualTo("软件工程概述");
        assertThat(intent.get().nodeCode()).isEqualTo("KT01");
    }

    @Test
    @DisplayName("blank / null 文本 → empty")
    void blankTextIsEmpty() {
        assertThat(PracticeIntentDetector.detect(null, List.of(SE_OVERVIEW), "KT01", "软件工程概述"))
                .isEmpty();
        assertThat(PracticeIntentDetector.detect("   ", List.of(SE_OVERVIEW), "KT01", "软件工程概述"))
                .isEmpty();
    }

    @Test
    @DisplayName("nodeCode 子串匹配")
    void matchesNodeCodeSubstring() {
        Optional<PracticeIntent> intent = PracticeIntentDetector.detect(
                "考我 KT01 两道题",
                List.of(SE_OVERVIEW),
                null,
                null);

        assertThat(intent).isPresent();
        assertThat(intent.get().nodeCode()).isEqualTo("KT01");
        assertThat(intent.get().size()).isEqualTo(2);
    }
}
