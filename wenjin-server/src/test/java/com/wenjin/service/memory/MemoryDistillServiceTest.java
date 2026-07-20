package com.wenjin.service.memory;

import com.wenjin.ai.MemoryDistillAiClient;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.entity.CompanionMessage;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.CompanionConversationMapper;
import com.wenjin.mapper.CompanionMessageMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.support.MemoryKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryDistillServiceTest {

    @Mock private CompanionConversationMapper conversationMapper;
    @Mock private CompanionMessageMapper messageMapper;
    @Mock private KgNodeMapper nodeMapper;
    @Mock private StudentMemoryService studentMemoryService;

    private RecordingDistillClient distillClient;
    private MemoryDistillService service;

    @BeforeEach
    void setUp() {
        distillClient = new RecordingDistillClient();
        service = new MemoryDistillService(
                conversationMapper, messageMapper, nodeMapper, distillClient, studentMemoryService);
    }

    @Test
    @DisplayName("蒸馏成功：夹紧配额、白名单过滤、推进 distilled watermark")
    void distill_appliesClampAndAdvancesWatermark() {
        CompanionConversation conv = new CompanionConversation();
        conv.setId(10L);
        conv.setStudentId(1L);
        conv.setCourseId(2L);
        conv.setDistilledThroughMessageId(5L);
        when(conversationMapper.selectById(10L)).thenReturn(conv);

        List<CompanionMessage> msgs = List.of(msg(6L, "u1"), msg(7L, "a1"), msg(8L, "u2"));
        when(messageMapper.selectSinceIdAsc(10L, 5L)).thenReturn(msgs);

        KgNode n1 = new KgNode();
        n1.setNodeCode("N1");
        when(nodeMapper.selectList(any())).thenReturn(List.of(n1));

        distillClient.items = List.of(
                new MemoryDistillAiClient.DistilledItem(MemoryKind.EPISODIC, "e1", "N1"),
                new MemoryDistillAiClient.DistilledItem(MemoryKind.EPISODIC, "e2", "N1"),
                new MemoryDistillAiClient.DistilledItem(MemoryKind.EPISODIC, "e3", "N1"),
                new MemoryDistillAiClient.DistilledItem(MemoryKind.EPISODIC, "e4-drop", "N1"),
                new MemoryDistillAiClient.DistilledItem(MemoryKind.SEMANTIC, "s1", "BAD"),
                new MemoryDistillAiClient.DistilledItem(MemoryKind.SEMANTIC, "s2", "N1"),
                new MemoryDistillAiClient.DistilledItem(MemoryKind.SEMANTIC, "s3-drop", "N1"));

        when(studentMemoryService.upsertOrReinforce(anyLong(), anyLong(), anyInt(), anyString(), any(), isNull()))
                .thenReturn(100L);
        when(conversationMapper.updateDistilledThroughGuarded(10L, 8L)).thenReturn(1);

        Map<String, Object> result = service.distill(10L, 8L);

        assertThat(result.get("watermarkAdvanced")).isEqualTo(true);
        assertThat(result.get("insertedOrReinforced")).isEqualTo(5); // 3 epi + 2 sem
        assertThat(result.get("candidates")).isEqualTo(5);
        verify(studentMemoryService).upsertOrReinforce(1L, 2L, MemoryKind.SEMANTIC, "s1", null, null);
        verify(conversationMapper).updateDistilledThroughGuarded(10L, 8L);
        assertThat(distillClient.lastWhitelist).containsExactly("N1");
    }

    @Test
    @DisplayName("已蒸馏过 through → 跳过，不调用 LLM")
    void alreadyDistilled_skips() {
        CompanionConversation conv = new CompanionConversation();
        conv.setId(10L);
        conv.setDistilledThroughMessageId(20L);
        when(conversationMapper.selectById(10L)).thenReturn(conv);

        Map<String, Object> result = service.distill(10L, 15L);

        assertThat(result.get("skipped")).isEqualTo("already_distilled");
        assertThat(distillClient.calls).isZero();
        verify(conversationMapper, never()).updateDistilledThroughGuarded(anyLong(), anyLong());
    }

    @Test
    @DisplayName("clamp：content 截断到 200，未知 kind 丢弃")
    void clamp_truncatesAndDropsUnknownKind() {
        String longContent = "x".repeat(250);
        List<MemoryDistillAiClient.DistilledItem> clamped = MemoryDistillService.clamp(
                List.of(
                        new MemoryDistillAiClient.DistilledItem(MemoryKind.EPISODIC, longContent, "N1"),
                        new MemoryDistillAiClient.DistilledItem(99, "bad", "N1")),
                Set.of("N1"));
        assertThat(clamped).hasSize(1);
        assertThat(clamped.get(0).content()).hasSize(200);
    }

    private static CompanionMessage msg(long id, String content) {
        CompanionMessage m = new CompanionMessage();
        m.setId(id);
        m.setContent(content);
        m.setRole(1);
        return m;
    }

    private static final class RecordingDistillClient implements MemoryDistillAiClient {
        List<DistilledItem> items = List.of();
        int calls;
        Set<String> lastWhitelist;

        @Override
        public List<DistilledItem> distill(List<CompanionMessage> messages, Set<String> nodeWhitelist) {
            calls++;
            lastWhitelist = nodeWhitelist;
            return new ArrayList<>(items);
        }
    }
}
