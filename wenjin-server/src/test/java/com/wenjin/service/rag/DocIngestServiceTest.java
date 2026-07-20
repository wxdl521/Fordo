package com.wenjin.service.rag;

import com.wenjin.entity.ChunkNodeMap;
import com.wenjin.entity.DocChunk;
import com.wenjin.entity.DocSource;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.ChunkNodeMapMapper;
import com.wenjin.mapper.DocChunkMapper;
import com.wenjin.mapper.DocSourceMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.support.ChunkMapReviewStatus;
import com.wenjin.support.DocSourceKind;
import com.wenjin.support.DocSourceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocIngestServiceTest {

    @Mock private DocSourceMapper sourceMapper;
    @Mock private DocChunkMapper chunkMapper;
    @Mock private ChunkNodeMapMapper mapMapper;
    @Mock private KgNodeMapper nodeMapper;

    private DocIngestService service;
    private final AtomicLong chunkIdSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        service = new DocIngestService(sourceMapper, chunkMapper, mapMapper, nodeMapper);
    }

    @Test
    @DisplayName("NODE_REF 样例入库：清障 → 多 chunk → APPROVED 映射 → READY")
    void ingestSample_ok() throws Exception {
        DocSource source = source(1L, 10L, DocSourceKind.NODE_REF);
        when(sourceMapper.selectById(1L)).thenReturn(source);
        when(nodeMapper.selectList(any())).thenReturn(List.of(node("SE-3.2"), node("SE-1.1")));
        when(chunkMapper.insert(any(DocChunk.class))).thenAnswer(inv -> {
            DocChunk c = inv.getArgument(0);
            c.setId(chunkIdSeq.incrementAndGet());
            return 1;
        });
        when(mapMapper.insert(any(ChunkNodeMap.class))).thenReturn(1);

        String md = new ClassPathResource("rag/node-ref/SE-3.2.md")
                .getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> result = service.ingestNodeRef(1L, md);

        assertThat(result.get("status")).isEqualTo("READY");
        assertThat(result.get("nodeCode")).isEqualTo("SE-3.2");
        assertThat((Integer) result.get("chunkCount")).isEqualTo(3);

        verify(mapMapper).deleteBySourceId(1L);
        verify(chunkMapper).deleteBySourceId(1L);
        verify(chunkMapper, times(3)).insert(any(DocChunk.class));
        ArgumentCaptor<ChunkNodeMap> mapCap = ArgumentCaptor.forClass(ChunkNodeMap.class);
        verify(mapMapper, times(3)).insert(mapCap.capture());
        assertThat(mapCap.getAllValues()).allMatch(m ->
                m.getReviewStatus() == ChunkMapReviewStatus.APPROVED
                        && "SE-3.2".equals(m.getNodeCode())
                        && m.getConfidence() != null);
        verify(sourceMapper).updateStatus(1L, DocSourceStatus.READY);
    }

    @Test
    @DisplayName("解析失败 → FAILED 且不插入 chunk")
    void parseFail_marksFailed() {
        when(sourceMapper.selectById(2L)).thenReturn(source(2L, 10L, DocSourceKind.NODE_REF));
        when(nodeMapper.selectList(any())).thenReturn(List.of(node("SE-3.2")));

        Map<String, Object> result = service.ingestNodeRef(2L, "not a valid doc");

        assertThat(result.get("status")).isEqualTo("FAILED");
        assertThat(result.get("error")).isEqualTo("PARSE_FAILED");
        verify(chunkMapper, never()).insert(any(DocChunk.class));
        verify(sourceMapper).updateStatus(2L, DocSourceStatus.FAILED);
    }

    @Test
    @DisplayName("重试清障：先删 map/chunk 再写，避免重复")
    void retryClearsHalfProducts() {
        when(sourceMapper.selectById(3L)).thenReturn(source(3L, 10L, DocSourceKind.NODE_REF));
        when(nodeMapper.selectList(any())).thenReturn(List.of(node("SE-3.2")));
        when(chunkMapper.insert(any(DocChunk.class))).thenAnswer(inv -> {
            DocChunk c = inv.getArgument(0);
            c.setId(chunkIdSeq.incrementAndGet());
            return 1;
        });

        String md = """
                ---
                node_code: SE-3.2
                title: t
                source_ref: s
                ---
                ## 概念
                body content here
                """;
        service.ingestNodeRef(3L, md);
        service.ingestNodeRef(3L, md);

        verify(mapMapper, times(2)).deleteBySourceId(3L);
        verify(chunkMapper, times(2)).deleteBySourceId(3L);
        verify(sourceMapper, times(2)).updateStatus(3L, DocSourceStatus.READY);
    }

    @Test
    @DisplayName("非 NODE_REF kind 拒绝")
    void wrongKind() {
        when(sourceMapper.selectById(4L)).thenReturn(source(4L, 10L, DocSourceKind.COURSEWARE));
        Map<String, Object> result = service.ingestNodeRef(4L, "x");
        assertThat(result.get("error")).isEqualTo("KIND_NOT_NODE_REF");
        verify(sourceMapper, atLeastOnce()).updateStatus(4L, DocSourceStatus.FAILED);
    }

    private static DocSource source(long id, long courseId, int kind) {
        DocSource s = new DocSource();
        s.setId(id);
        s.setCourseId(courseId);
        s.setKind(kind);
        s.setTitle("t");
        s.setStatus(DocSourceStatus.INGESTING);
        s.setCreatedBy(7L);
        return s;
    }

    private static KgNode node(String code) {
        KgNode n = new KgNode();
        n.setNodeCode(code);
        return n;
    }
}
