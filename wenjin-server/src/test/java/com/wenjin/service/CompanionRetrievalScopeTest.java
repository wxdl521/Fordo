package com.wenjin.service;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.config.LimitsProperties;
import com.wenjin.mapper.CompanionConversationMapper;
import com.wenjin.mapper.CompanionMessageMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.rag.RetrievalHit;
import com.wenjin.service.rag.RetrievalScopeResolver;
import com.wenjin.service.rag.RetrievalService;
import com.wenjin.service.rag.RetrieverPort;
import com.wenjin.support.ConversationContextBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * R2：伴侣 RETRIEVAL 注入的节点范围 = 焦点 + 直接前置；resolver 缺省 / 失败时只用焦点。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanionRetrievalScopeTest {

    @Mock private CompanionConversationMapper conversationMapper;
    @Mock private CompanionMessageMapper messageMapper;
    @Mock private KgNodeMapper nodeMapper;
    @Mock private StudentMasteryMapper masteryMapper;
    @Mock private CourseMapper courseMapper;
    @Mock private PathService pathService;
    @Mock private DiagnosticResultService diagnosticResultService;
    @Mock private CompanionAiClient aiClient;
    @Mock private CourseService courseService;
    @Mock private RetrievalScopeResolver scopeResolver;

    private CompanionServiceImpl service;
    private final List<List<String>> scopes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        LimitsProperties limits = new LimitsProperties();
        service = new CompanionServiceImpl(
                conversationMapper, messageMapper, nodeMapper, masteryMapper, courseMapper,
                pathService, diagnosticResultService, aiClient, new MasteryDecayCalculator(),
                courseService, new ConversationContextBuilder(limits), limits);
        RetrieverPort recording = (courseId, nodeCodes, query, topK) -> {
            scopes.add(nodeCodes);
            return List.of(new RetrievalHit(1L, "内容", "教材", "{}", 1.0));
        };
        ReflectionTestUtils.setField(service, "retrievalService", new RetrievalService(recording));
    }

    @Test
    @DisplayName("resolver 存在 → 检索范围为焦点 + 直接前置")
    void expandsWithPrereqs() {
        ReflectionTestUtils.setField(service, "retrievalScopeResolver", scopeResolver);
        when(scopeResolver.scopeFor(5L, "KT05")).thenReturn(List.of("KT05", "KT03"));

        var injection = service.loadRetrieval(5L, "KT05", "需求是什么");

        assertThat(scopes).containsExactly(List.of("KT05", "KT03"));
        assertThat(injection.chunkIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("resolver 缺省 → 只焦点（旧行为）；resolver 抛错 → 回退焦点")
    void fallbackFocusOnly() {
        service.loadRetrieval(5L, "KT05", "需求是什么");
        ReflectionTestUtils.setField(service, "retrievalScopeResolver", scopeResolver);
        when(scopeResolver.scopeFor(5L, "KT05")).thenThrow(new IllegalStateException("db"));
        service.loadRetrieval(5L, "KT05", "需求是什么");
        service.loadRetrieval(5L, null, "需求是什么");

        assertThat(scopes).containsExactly(List.of("KT05"), List.of("KT05"), List.of());
    }
}
