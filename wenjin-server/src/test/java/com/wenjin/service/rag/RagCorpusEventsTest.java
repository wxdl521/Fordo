package com.wenjin.service.rag;

import com.wenjin.dto.BatchApproveMapsRequest;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.entity.ChunkNodeMap;
import com.wenjin.entity.DocSource;
import com.wenjin.mapper.ChunkNodeMapMapper;
import com.wenjin.mapper.DocChunkMapper;
import com.wenjin.mapper.DocSourceMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.AiJobPayloadSubmissionService;
import com.wenjin.support.ChunkMapReviewStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2 失效钩子：映射通过 / 驳回 / 批量通过发 RagCorpusChangedEvent；监听器失效索引并派发嵌入，失败只 warn；
 * 来源列表的嵌入覆盖率。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagCorpusEventsTest {

    @Mock private DocSourceMapper sourceMapper;
    @Mock private DocChunkMapper chunkMapper;
    @Mock private ChunkNodeMapMapper mapMapper;
    @Mock private DocIngestService docIngestService;
    @Mock private CourseAccessService courseAccessService;
    @Mock private AiJobPayloadSubmissionService submissions;
    @Mock private ApplicationEventPublisher events;
    @Mock private RagEmbedService ragEmbedService;
    @Mock private VectorIndexCache indexCache;

    private TeacherRagService service;

    @BeforeEach
    void setUp() {
        service = new TeacherRagService(sourceMapper, chunkMapper, mapMapper, docIngestService,
                courseAccessService, new FeatureFlagsProperties(), submissions);
        service.setEventPublisher(events);
        service.setRagEmbedService(ragEmbedService);
        CurrentUser.set(new CurrentPrincipal(42L, UserRole.TEACHER));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    private ChunkNodeMap map(long id) {
        ChunkNodeMap m = new ChunkNodeMap();
        m.setId(id);
        m.setChunkId(100L + id);
        m.setCourseId(7L);
        m.setNodeCode("N1");
        m.setConfidence(new BigDecimal("0.800"));
        m.setReviewStatus(ChunkMapReviewStatus.PENDING);
        return m;
    }

    @Test
    @DisplayName("approve / reject / batch-approve → 发课程语料变化事件")
    void reviewPublishes() {
        when(mapMapper.selectById(1L)).thenReturn(map(1));
        service.approve(1L);
        verify(events).publishEvent(new RagCorpusChangedEvent(7L, "MAP_APPROVE"));

        when(mapMapper.selectById(2L)).thenReturn(map(2));
        service.reject(2L);
        verify(events).publishEvent(new RagCorpusChangedEvent(7L, "MAP_REJECT"));

        when(mapMapper.selectList(any())).thenReturn(List.of(map(3), map(4)));
        assertThat(service.batchApprove(7L, new BatchApproveMapsRequest())).isEqualTo(2);
        verify(events).publishEvent(new RagCorpusChangedEvent(7L, "MAP_BATCH_APPROVE"));
    }

    @Test
    @DisplayName("batch-approve 无可通过项 → 不发事件")
    void batchNothingNoEvent() {
        when(mapMapper.selectList(any())).thenReturn(List.of());
        service.batchApprove(7L, new BatchApproveMapsRequest());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("监听器：语料变化 → 索引失效；入库成功 → 失效 + 派发；派发失败只 warn 不抛")
    void listener() {
        RagCorpusEventListener listener = new RagCorpusEventListener(indexCache, ragEmbedService);
        listener.onCorpusChanged(new RagCorpusChangedEvent(7L, "MAP_APPROVE"));
        verify(indexCache).invalidateCourse(7L);

        RagSourceIngestedEvent ingested = new RagSourceIngestedEvent(8L, 5L, 42L, 1L);
        doThrow(new IllegalStateException("db down")).when(ragEmbedService).dispatchAfterIngest(ingested);
        listener.onSourceIngested(ingested);
        verify(indexCache).invalidateCourse(8L);
        verify(ragEmbedService).dispatchAfterIngest(ingested);
    }

    @Test
    @DisplayName("来源列表：向量模式开 → embeddedChunks/totalChunks/embeddingModel；关 → embeddedChunks=null")
    void listSourcesCoverage() {
        DocSource s = new DocSource();
        s.setId(5L);
        s.setCourseId(7L);
        s.setStatus(1);
        when(sourceMapper.selectList(any())).thenReturn(List.of(s));
        when(chunkMapper.selectCount(any())).thenReturn(10L);
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(ragEmbedService.activeModelId()).thenReturn(Optional.of("hash-ngram@1024"));
        when(ragEmbedService.embeddedCount(5L, "hash-ngram@1024")).thenReturn(4L);

        var vo = service.listSources(7L).get(0);
        assertThat(vo.getTotalChunks()).isEqualTo(10L);
        assertThat(vo.getEmbeddedChunks()).isEqualTo(4L);
        assertThat(vo.getEmbeddingModel()).isEqualTo("hash-ngram@1024");

        when(ragEmbedService.activeModelId()).thenReturn(Optional.empty());
        var off = service.listSources(7L).get(0);
        assertThat(off.getEmbeddedChunks()).isNull();
        assertThat(off.getTotalChunks()).isEqualTo(10L);
        verify(ragEmbedService, org.mockito.Mockito.times(1)).embeddedCount(org.mockito.ArgumentMatchers.eq(5L), anyString());
    }
}
