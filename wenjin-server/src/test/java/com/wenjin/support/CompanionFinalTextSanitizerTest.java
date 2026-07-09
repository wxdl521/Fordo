package com.wenjin.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompanionFinalTextSanitizerTest {

    @Test
    void stripsDsmlToolCallsBlockToEmpty() {
        String raw = """
                <|DSML|tool_calls>
                <|DSML|invoke name="get_practice_questions">
                <|DSML|parameter name="sessionid" string="false">13</|DSML|parameter>
                </|DSML|invoke>
                </|DSML|tool_calls>
                """;
        assertThat(CompanionFinalTextSanitizer.sanitize(raw)).isEmpty();
    }

    @Test
    void keepsNaturalLanguageAndDropsProtocolTail() {
        String raw = "已为你生成练习。\n<|DSML|tool_calls>\nx</|DSML|tool_calls>";
        assertThat(CompanionFinalTextSanitizer.sanitize(raw)).isEqualTo("已为你生成练习。");
    }

    @Test
    void leavesNormalChineseUntouched() {
        assertThat(CompanionFinalTextSanitizer.sanitize("建议你先复习软件工程概述。"))
                .isEqualTo("建议你先复习软件工程概述。");
    }

    @Test
    void nullAndBlankBecomeEmpty() {
        assertThat(CompanionFinalTextSanitizer.sanitize(null)).isEmpty();
        assertThat(CompanionFinalTextSanitizer.sanitize("   ")).isEmpty();
    }
}
