package com.wenjin.config;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.ai.GraphCleanAiClient;
import com.wenjin.ai.ImageToMarkdownAiClient;
import com.wenjin.ai.QuestionAiClient;
import com.wenjin.ai.QuestionBankCleanAiClient;
import com.wenjin.ai.SyllabusGraphAiClient;
import com.wenjin.ai.dto.AiAnnotation;
import com.wenjin.ai.dto.AiQuestion;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.QuestionBankFile;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 集成测试用 Fake AI：@Primary 覆盖真实 OpenAI 客户端，禁止出网、不依赖 API Key。
 */
@TestConfiguration
public class FakeAiConfiguration {

    private static BusinessException disabled() {
        return new BusinessException(ResultCode.AI_ERROR, "Fake AI: no external AI calls in tests");
    }

    @Bean
    @Primary
    CompanionAiClient fakeCompanionAiClient() {
        return new CompanionAiClient() {
            @Override
            public void stream(String systemPrompt, List<ChatMsg> history, String userMessage,
                               Consumer<String> onToken) {
                throw disabled();
            }

            @Override
            public TurnOutcome completeWithTools(List<Map<String, Object>> messages,
                                                 List<Map<String, Object>> toolSchemas) {
                throw disabled();
            }

            @Override
            public void streamMessages(List<Map<String, Object>> messages, Consumer<String> onToken) {
                throw disabled();
            }
        };
    }

    @Bean
    @Primary
    QuestionAiClient fakeQuestionAiClient() {
        return new QuestionAiClient() {
            @Override
            public List<AiQuestion> generate(String targetNodeCode, String targetName,
                                             String chapter, int count, List<String[]> whitelist) {
                throw disabled();
            }

            @Override
            public AiAnnotation annotate(String stem, List<String[]> options, List<String[]> whitelist) {
                throw disabled();
            }

            @Override
            public String explainLearningStep(String nodeName, String roleLabel, String targetName) {
                throw disabled();
            }
        };
    }

    @Bean
    @Primary
    GraphCleanAiClient fakeGraphCleanAiClient() {
        return (rawNodes, rawEdges) -> {
            GraphImportRequest req = new GraphImportRequest();
            req.setNodes(rawNodes == null ? Collections.emptyList() : rawNodes);
            req.setEdges(rawEdges == null ? Collections.emptyList() : rawEdges);
            return req;
        };
    }

    @Bean
    @Primary
    QuestionBankCleanAiClient fakeQuestionBankCleanAiClient() {
        return (List<QuestionBankFile.BankQuestion> raw) -> {
            QuestionBankFile file = new QuestionBankFile();
            file.setQuestions(raw == null ? Collections.emptyList() : raw);
            return file;
        };
    }

    @Bean
    @Primary
    ImageToMarkdownAiClient fakeImageToMarkdownAiClient() {
        return jpegImageBytes -> "# fake-markdown";
    }

    @Bean
    @Primary
    SyllabusGraphAiClient fakeSyllabusGraphAiClient() {
        return syllabusText -> {
            GraphImportRequest req = new GraphImportRequest();
            req.setNodes(Collections.emptyList());
            req.setEdges(Collections.emptyList());
            return req;
        };
    }
}
