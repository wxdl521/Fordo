package com.wenjin.ai;

import com.wenjin.dto.QuestionBankFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAiCompatibleQuestionBankCleanAiClient 单元测试：覆盖 prompt 构造与 JSON 解析（无网络调用）。
 */
class OpenAiCompatibleQuestionBankCleanAiClientTest {

    // ============================ prompt 构造 ============================

    @Test
    @DisplayName("prompt 含清洗规则与干扰项考点映射规则，schema 含 point_node_code")
    void buildCleanPromptContainsPointNodeCodeRuleAndSchema() {
        String prompt = OpenAiCompatibleQuestionBankCleanAiClient.buildCleanPrompt(List.of());

        assertThat(prompt).contains("difficulty 标准化");
        assertThat(prompt).contains("干扰项考点映射");
        assertThat(prompt).contains("point_node_code");
        assertThat(prompt).contains("以 KT 开头");
    }

    // ============================ JSON 解析 ============================

    @Test
    @DisplayName("解析读取选项的 point_node_code：干扰项保留、正确项 null")
    void parseCleanResultReadsPointNodeCode() {
        String json = """
                {"questions":[
                  {"stem":"题干","nodeCode":"KT07","chapter":"第1章","difficulty":3,"analysis":"解析",
                   "options":[
                     {"key":"A","text":"正确项","correct":true,"point_node_code":null},
                     {"key":"B","text":"误解项","correct":false,"point_node_code":"KT05"}
                   ]}
                ]}""";

        QuestionBankFile result = OpenAiCompatibleQuestionBankCleanAiClient.parseCleanResult(json);

        assertThat(result.getQuestions()).hasSize(1);
        List<QuestionBankFile.BankOption> opts = result.getQuestions().get(0).getOptions();
        assertThat(opts).hasSize(2);
        assertThat(opts.get(0).getCorrect()).isTrue();
        assertThat(opts.get(0).getPointNodeCode()).isNull();
        assertThat(opts.get(1).getCorrect()).isFalse();
        assertThat(opts.get(1).getPointNodeCode()).isEqualTo("KT05");
    }

    @Test
    @DisplayName("解析缺省 point_node_code 字段时为 null")
    void parseCleanResultMissingPointNodeCode() {
        String json = """
                {"questions":[
                  {"stem":"题干","nodeCode":"KT07","difficulty":3,"analysis":"解析",
                   "options":[{"key":"A","text":"x","correct":true}]}
                ]}""";

        QuestionBankFile result = OpenAiCompatibleQuestionBankCleanAiClient.parseCleanResult(json);

        assertThat(result.getQuestions().get(0).getOptions().get(0).getPointNodeCode()).isNull();
    }
}
