package com.wenjin.service.rag;

import com.wenjin.entity.ChunkNodeMap;
import com.wenjin.entity.DocChunk;
import com.wenjin.entity.DocSource;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.ChunkNodeMapMapper;
import com.wenjin.mapper.DocChunkMapper;
import com.wenjin.mapper.DocSourceMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.support.DocSourceKind;
import com.wenjin.support.DocSourceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2：入库成功（NODE_REF 与文件两条路径）发 RagSourceIngestedEvent；失败不发；事件发布异常不影响入库；
 * 重新入库清障时删除旧向量。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocIngestEventsTest {

    private static final String NODE_REF_MD = """
            ---
            node_code: SE-3.2
            title: t
            source_ref: s
            ---
            ## 概念
            body content here
            """;

    @Mock private DocSourceMapper sourceMapper;
    @Mock private DocChunkMapper chunkMapper;
    @Mock private ChunkNodeMapMapper mapMapper;
    @Mock private KgNodeMapper nodeMapper;
    @Mock private ApplicationEventPublisher events;
    @Mock private ChunkEmbeddingRepository embeddingRepository;

    private DocIngestService service;
    private final AtomicLong chunkIdSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        service = new DocIngestService(sourceMapper, chunkMapper, mapMapper, nodeMapper,
                new com.wenjin.support.DocumentTextExtractor());
        service.setEventPublisher(events);
        service.setChunkEmbeddingRepository(embeddingRepository);
        when(chunkMapper.insert(any(DocChunk.class))).thenAnswer(inv -> {
            DocChunk c = inv.getArgument(0);
            c.setId(chunkIdSeq.incrementAndGet());
            return 1;
        });
        when(mapMapper.insert(any(ChunkNodeMap.class))).thenReturn(1);
        KgNode n = new KgNode();
        n.setNodeCode("SE-3.2");
        n.setName("需求分析");
        when(nodeMapper.selectList(any())).thenReturn(List.of(n));
    }

    private static DocSource source(long id, int kind) {
        DocSource s = new DocSource();
        s.setId(id);
        s.setCourseId(10L);
        s.setKind(kind);
        s.setTitle("t");
        s.setStatus(DocSourceStatus.INGESTING);
        s.setCreatedBy(42L);
        return s;
    }

    @Test
    @DisplayName("NODE_REF 同步路径成功 → 发事件（courseId, sourceId, 创建教师, 最小 chunkId）；先删旧向量")
    void nodeRefPublishes() {
        when(sourceMapper.selectById(1L)).thenReturn(source(1L, DocSourceKind.NODE_REF));

        Map<String, Object> r = service.ingestNodeRef(1L, NODE_REF_MD);

        assertThat(r.get("status")).isEqualTo("READY");
        verify(events).publishEvent(new RagSourceIngestedEvent(10L, 1L, 42L, 1001L));
        var order = inOrder(embeddingRepository, chunkMapper);
        order.verify(embeddingRepository).deleteBySourceId(1L);
        order.verify(chunkMapper).deleteBySourceId(1L);
    }

    @Test
    @DisplayName("文件入库（DOC_INGEST 作业路径）成功 → 发事件")
    void documentPublishes() {
        when(sourceMapper.selectById(2L)).thenReturn(source(2L, DocSourceKind.COURSEWARE));

        Map<String, Object> r = service.ingestSource(2L,
                "# 需求分析\n\n需求分析是软件工程的重要阶段。".getBytes(StandardCharsets.UTF_8), "a.md");

        assertThat(r.get("status")).isEqualTo("READY");
        verify(events).publishEvent(any(RagSourceIngestedEvent.class));
    }

    @Test
    @DisplayName("解析失败 → 不发事件")
    void failureNoEvent() {
        when(sourceMapper.selectById(3L)).thenReturn(source(3L, DocSourceKind.NODE_REF));
        assertThat(service.ingestNodeRef(3L, "not a valid doc").get("status")).isEqualTo("FAILED");
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("事件发布抛错（如嵌入派发失败）→ 入库仍 READY")
    void publishFailureDoesNotFailIngest() {
        when(sourceMapper.selectById(4L)).thenReturn(source(4L, DocSourceKind.NODE_REF));
        doThrow(new IllegalStateException("boom")).when(events).publishEvent(any(Object.class));

        assertThat(service.ingestNodeRef(4L, NODE_REF_MD).get("status")).isEqualTo("READY");
        verify(sourceMapper).updateStatus(4L, DocSourceStatus.READY);
    }
}
