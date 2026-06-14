package com.wenjin.ai;

import com.wenjin.ai.dto.AiAnnotation;
import com.wenjin.ai.dto.AiDistractor;
import com.wenjin.ai.dto.AiQuestion;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAiCompatibleQuestionAiClient 的纯单元测试：只验证 prompt 构造与 JSON 解析，不联网。
 */
class OpenAiCompatibleQuestionAiClientTest {

    // ---------- parseQuestions ----------

    @Test
    @DisplayName("parseQuestions：解析带 ```json 围栏的 {\"questions\":[...]} 外层对象")
    void parseQuestions_withJsonFenceAndWrapper() {
        String content = """
                这是模型的一些前言。
                ```json
                {"questions":[
                  {"stem":"在软件工程中，需求分析的主要目的是？",
                   "options":[
                     {"key":"A","text":"明确系统应当做什么","correct":true,"point_node_code":null},
                     {"key":"B","text":"编写代码","correct":false,"point_node_code":"KT05"},
                     {"key":"C","text":"部署上线","correct":false,"point_node_code":"KT06"},
                     {"key":"D","text":"性能压测","correct":false,"point_node_code":"KT08"}
                   ],
                   "analysis":"需求分析关注问题域而非实现。",
                   "difficulty":3,
                   "main_point":"KT07",
                   "sub_points":["KT05"]}
                ]}
                ```
                """;

        List<AiQuestion> questions = OpenAiCompatibleQuestionAiClient.parseQuestions(content);

        assertThat(questions).hasSize(1);
        AiQuestion q = questions.get(0);
        assertThat(q.getStem()).contains("需求分析");
        assertThat(q.getDifficulty()).isEqualTo(3);
        assertThat(q.getMainPoint()).isEqualTo("KT07");
        assertThat(q.getSubPoints()).containsExactly("KT05");

        assertThat(q.getOptions()).hasSize(4);
        // 恰一个 correct=true
        assertThat(q.getOptions()).filteredOn(o -> Boolean.TRUE.equals(o.getCorrect())).hasSize(1);
        AiDistractor correct = q.getOptions().stream()
                .filter(o -> Boolean.TRUE.equals(o.getCorrect())).findFirst().orElseThrow();
        assertThat(correct.getOptionKey()).isEqualTo("A");
        assertThat(correct.getPointNodeCode()).isNull();
        // 干扰项的 point_node_code 正确映射到 camelCase
        AiDistractor b = q.getOptions().stream()
                .filter(o -> "B".equals(o.getOptionKey())).findFirst().orElseThrow();
        assertThat(b.getCorrect()).isFalse();
        assertThat(b.getPointNodeCode()).isEqualTo("KT05");
    }

    @Test
    @DisplayName("parseQuestions：兼容裸数组 [{...}]（无 questions 外层包裹）")
    void parseQuestions_bareArray() {
        String content = """
                [
                  {"stem":"裸数组题干",
                   "options":[
                     {"key":"A","text":"对","correct":true,"point_node_code":null},
                     {"key":"B","text":"错","correct":false,"point_node_code":"KT01"}
                   ],
                   "analysis":"说明",
                   "difficulty":2,
                   "main_point":"KT02",
                   "sub_points":[]}
                ]
                """;

        List<AiQuestion> questions = OpenAiCompatibleQuestionAiClient.parseQuestions(content);

        assertThat(questions).hasSize(1);
        AiQuestion q = questions.get(0);
        assertThat(q.getStem()).isEqualTo("裸数组题干");
        assertThat(q.getMainPoint()).isEqualTo("KT02");
        assertThat(q.getOptions()).hasSize(2);
        assertThat(q.getOptions().get(1).getPointNodeCode()).isEqualTo("KT01");
    }

    @Test
    @DisplayName("parseQuestions/parseAnnotation：非法 JSON 抛 BusinessException 且 code==AI_ERROR")
    void parse_invalidJson_throwsAiError() {
        assertThatThrownBy(() -> OpenAiCompatibleQuestionAiClient.parseQuestions("not json at all"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.AI_ERROR.getCode());

        assertThatThrownBy(() -> OpenAiCompatibleQuestionAiClient.parseAnnotation("{ broken"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.AI_ERROR.getCode());
    }

    // ---------- parseAnnotation ----------

    @Test
    @DisplayName("parseAnnotation：超纲返回 main_point=null 且 reason 非空")
    void parseAnnotation_outOfScope() {
        String content = "{\"main_point\":null,\"reason\":\"超纲：不在本课程范围\"}";

        AiAnnotation ann = OpenAiCompatibleQuestionAiClient.parseAnnotation(content);

        assertThat(ann.getMainPoint()).isNull();
        assertThat(ann.getReason()).isNotBlank();
    }

    @Test
    @DisplayName("parseAnnotation：在纲内 main_point 有值且 distractors 解析正确")
    void parseAnnotation_inScope() {
        String content = """
                ```json
                {"main_point":"KT10",
                 "sub_points":["KT07","KT08"],
                 "distractors":[{"key":"B","point_node_code":"KT07"},
                                {"key":"C","point_node_code":"KT08"}],
                 "reason":"题干考查 KT10，干扰项分别错在 KT07/KT08"}
                ```
                """;

        AiAnnotation ann = OpenAiCompatibleQuestionAiClient.parseAnnotation(content);

        assertThat(ann.getMainPoint()).isEqualTo("KT10");
        assertThat(ann.getSubPoints()).containsExactly("KT07", "KT08");
        assertThat(ann.getDistractors()).hasSize(2);
        assertThat(ann.getDistractors().get(0).getOptionKey()).isEqualTo("B");
        assertThat(ann.getDistractors().get(0).getPointNodeCode()).isEqualTo("KT07");
        assertThat(ann.getReason()).isNotBlank();
    }

    // ---------- buildGeneratePrompt ----------

    @Test
    @DisplayName("buildGeneratePrompt：包含目标 code、数量、每个白名单 code 及约束措辞")
    void buildGeneratePrompt_containsKeyParts() {
        List<String[]> whitelist = List.of(
                new String[]{"KT07", "需求分析"},
                new String[]{"KT05", "可行性研究"},
                new String[]{"KT06", "软件过程模型"});

        String prompt = OpenAiCompatibleQuestionAiClient.buildGeneratePrompt(
                "KT07", "需求分析", "第3章 需求工程", 5, whitelist);

        // 目标节点 code 与数量
        assertThat(prompt).contains("KT07");
        assertThat(prompt).contains("5");
        // 章节
        assertThat(prompt).contains("第3章 需求工程");
        // 每个白名单 code 都出现
        assertThat(prompt).contains("KT07").contains("KT05").contains("KT06");
        // 白名单名称也在
        assertThat(prompt).contains("需求分析").contains("可行性研究").contains("软件过程模型");
        // 约束措辞
        assertThat(prompt).contains("单选");
        assertThat(prompt).contains("恰一个");
        assertThat(prompt).contains("范围");
        // 期望的输出结构提示
        assertThat(prompt).contains("questions");
        assertThat(prompt).contains("point_node_code");
    }

    // ---------- buildAnnotatePrompt ----------

    @Test
    @DisplayName("buildAnnotatePrompt：包含题干、各选项文本、白名单 code 与超纲规则")
    void buildAnnotatePrompt_containsKeyParts() {
        List<String[]> options = List.of(
                new String[]{"A", "明确系统应当做什么"},
                new String[]{"B", "编写代码"});
        List<String[]> whitelist = List.of(
                new String[]{"KT07", "需求分析"},
                new String[]{"KT05", "可行性研究"});

        String prompt = OpenAiCompatibleQuestionAiClient.buildAnnotatePrompt(
                "需求分析的主要目的是？", options, whitelist);

        assertThat(prompt).contains("需求分析的主要目的是？");
        assertThat(prompt).contains("明确系统应当做什么");
        assertThat(prompt).contains("编写代码");
        assertThat(prompt).contains("KT07").contains("KT05");
        // 超纲规则
        assertThat(prompt).contains("超纲");
        assertThat(prompt).contains("main_point");
    }
}
