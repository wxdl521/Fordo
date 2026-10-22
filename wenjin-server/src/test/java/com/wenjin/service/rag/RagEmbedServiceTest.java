package com.wenjin.service.rag;

import com.wenjin.ai.EmbeddingClient;
import com.wenjin.common.BusinessException;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.AiJobVO;
import com.wenjin.entity.DocChunk;
import com.wenjin.entity.DocSource;
import com.wenjin.mapper.DocChunkMapper;
import com.wenjin.mapper.DocSourceMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.AiJobService;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.DocSourceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagEmbedServiceTest {

    private static final String MODEL = "emb-x@4";

    @Mock private DocSourceMapper sourceMapper;
    @Mock private DocChunkMapper chunkMapper;
    @Mock private ChunkEmbeddingRepository embeddingRepository;
    @Mock private AiJobService aiJobService;
    @Mock private CourseAccessService courseAccessService;
    @Mock private ApplicationEventPublisher events;

    private FeatureFlagsProperties flags;
    private LimitsProperties limits;
    private FakeClient client;
    private RagEmbedService service;
    private final List<Integer> progress = new ArrayList<>();

    @BeforeEach
    void setUp() {
        flags = new FeatureFlagsProperties();
        limits = new LimitsProperties();
        client = new FakeClient();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(600_000L * 60), ZoneOffset.UTC);
        service = new RagEmbedService(new ActiveEmbeddingResolver(flags, limits, client), sourceMapper, chunkMapper,
                embeddingRepository, aiJobService, courseAccessService, flags, events, clock);
        DocSource src = new DocSource();
        src.setId(5L);
        src.setCourseId(7L);
        src.setStatus(DocSourceStatus.READY);
        when(sourceMapper.selectById(5L)).thenReturn(src);
    }

    private static DocChunk chunk(long id, String content) {
        DocChunk c = new DocChunk();
        c.setId(id);
        c.setContent(content);
        return c;
    }

    @Test
    @DisplayName("执行：content_sha 未变的块跳过，其余批量嵌入并 upsert；完成后发索引失效事件")
    void embedSkipsUnchanged() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1, "甲"), chunk(2, "乙"), chunk(3, "丙")));
        when(embeddingRepository.loadShaByChunkIds(eq(MODEL), anyCollection()))
                .thenReturn(Map.of(1L, ChunkEmbeddingRepository.contentSha("甲"), 2L, "stale-sha"));

        Map<String, Object> r = service.embedSource(5L, 7L, progress::add);

        assertThat(client.batches).containsExactly(List.of("乙", "丙"));
        verify(embeddingRepository).upsert(eq(2L), eq(MODEL), any(), eq(ChunkEmbeddingRepository.contentSha("乙")));
        verify(embeddingRepository).upsert(eq(3L), eq(MODEL), any(), eq(ChunkEmbeddingRepository.contentSha("丙")));
        verify(embeddingRepository, never()).upsert(eq(1L), anyString(), any(), anyString());
        assertThat(r).containsEntry("status", "DONE").containsEntry("total", 3)
                .containsEntry("embedded", 2).containsEntry("skipped", 1).containsEntry("model", MODEL);
        assertThat(progress).containsExactly(90);
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isEqualTo(new RagCorpusChangedEvent(7L, "EMBED_DONE"));
    }

    @Test
    @DisplayName("执行幂等：全部未变 → 不调用嵌入、不发事件")
    void embedIdempotent() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1, "甲")));
        when(embeddingRepository.loadShaByChunkIds(eq(MODEL), anyCollection()))
                .thenReturn(Map.of(1L, ChunkEmbeddingRepository.contentSha("甲")));

        Map<String, Object> r = service.embedSource(5L, 7L, progress::add);

        assertThat(client.batches).isEmpty();
        assertThat(r).containsEntry("embedded", 0).containsEntry("skipped", 1);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("执行：超过 EMBED_BATCH 分批并逐批上报进度")
    void embedBatches() {
        List<DocChunk> many = new ArrayList<>();
        for (int i = 1; i <= RagEmbedService.EMBED_BATCH + 3; i++) {
            many.add(chunk(i, "c" + i));
        }
        when(chunkMapper.selectList(any())).thenReturn(many);
        when(embeddingRepository.loadShaByChunkIds(eq(MODEL), anyCollection())).thenReturn(Map.of());

        service.embedSource(5L, 7L, progress::add);

        assertThat(client.batches).hasSize(2);
        assertThat(client.batches.get(1)).hasSize(3);
        assertThat(progress).hasSize(2).last().isEqualTo(90);
        verify(embeddingRepository, times(RagEmbedService.EMBED_BATCH + 3)).upsert(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("执行：向量模式关 → SKIPPED；来源不 READY → SKIPPED；跨课程 → 404")
    void embedSkipsAndGuards() {
        flags.setRagVectorEnabled(false);
        assertThat(service.embedSource(5L, 7L, null)).containsEntry("status", "SKIPPED").containsEntry("reason", "VECTOR_OFF");
        flags.setRagVectorEnabled(true);

        assertThatThrownBy(() -> service.embedSource(5L, 8L, null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(404);

        DocSource ingesting = new DocSource();
        ingesting.setId(6L);
        ingesting.setCourseId(7L);
        ingesting.setStatus(DocSourceStatus.INGESTING);
        when(sourceMapper.selectById(6L)).thenReturn(ingesting);
        assertThat(service.embedSource(6L, 7L, null)).containsEntry("reason", "SOURCE_NOT_READY");
        assertThat(client.batches).isEmpty();
    }

    @Test
    @DisplayName("执行：嵌入失败抛出（作业 FAILED），已写入的批次仍发索引失效")
    void embedFailurePropagates() {
        List<DocChunk> many = new ArrayList<>();
        for (int i = 1; i <= RagEmbedService.EMBED_BATCH + 1; i++) {
            many.add(chunk(i, "c" + i));
        }
        when(chunkMapper.selectList(any())).thenReturn(many);
        when(embeddingRepository.loadShaByChunkIds(eq(MODEL), anyCollection())).thenReturn(Map.of());
        client.failOnCall = 2;

        assertThatThrownBy(() -> service.embedSource(5L, 7L, progress::add)).isInstanceOf(BusinessException.class);
        verify(events).publishEvent(new RagCorpusChangedEvent(7L, "EMBED_DONE"));
    }

    @Test
    @DisplayName("自动派发：向量模式开 → enqueueInternal(RAG_EMBED, emb:{sid}:{model}:{minChunkId})；关 / 无属主 → 不派发")
    void dispatchAfterIngest() {
        AiJobVO vo = new AiJobVO();
        vo.setId(99L);
        when(aiJobService.enqueueInternal(eq(7L), eq(42L), eq(AiJobKind.RAG_EMBED), anyString(), any())).thenReturn(vo);

        AiJobVO job = service.dispatchAfterIngest(new RagSourceIngestedEvent(7L, 5L, 42L, 300L));

        assertThat(job).isSameAs(vo);
        verify(aiJobService).enqueueInternal(eq(7L), eq(42L), eq(AiJobKind.RAG_EMBED),
                eq("emb:5:" + MODEL + ":300"), eq(Map.of("sourceId", 5L, "model", MODEL)));

        assertThat(service.dispatchAfterIngest(new RagSourceIngestedEvent(7L, 5L, null, 300L))).isNull();
        flags.setRagVectorEnabled(false);
        assertThat(service.dispatchAfterIngest(new RagSourceIngestedEvent(7L, 5L, 42L, 300L))).isNull();
        verify(aiJobService, times(1)).enqueueInternal(any(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("幂等键 ≤ 64：超长 modelId 换成 SHA-256 前 16 位；重建键带分钟")
    void keys() {
        assertThat(RagEmbedService.autoKey(5, "embedding-3@1024", 12)).isEqualTo("emb:5:embedding-3@1024:12");
        assertThat(RagEmbedService.reembedKey(5, "embedding-3@1024", 600000)).isEqualTo("emb:5:embedding-3@1024:r600000");
        String longModel = "m".repeat(80) + "@1024";
        String k = RagEmbedService.autoKey(123456789L, longModel, 987654321L);
        assertThat(k).hasSizeLessThanOrEqualTo(64).startsWith("emb:123456789:").endsWith(":987654321");
        assertThat(RagEmbedService.autoKey(123456789L, longModel, 987654321L)).isEqualTo(k);
    }

    @Test
    @DisplayName("重建向量：开关关 404；无客户端 503；否则按 READY 来源 dispatch（教师身份 + 管理权校验）")
    void reembedCourse() {
        DocSource a = new DocSource();
        a.setId(5L);
        DocSource b = new DocSource();
        b.setId(6L);
        when(sourceMapper.selectList(any())).thenReturn(List.of(a, b));
        AiJobVO j1 = new AiJobVO();
        j1.setId(1L);
        AiJobVO j2 = new AiJobVO();
        j2.setId(2L);
        when(aiJobService.dispatch(eq(7L), eq(AiJobKind.RAG_EMBED), eq("emb:5:" + MODEL + ":r600000"), any())).thenReturn(j1);
        when(aiJobService.dispatch(eq(7L), eq(AiJobKind.RAG_EMBED), eq("emb:6:" + MODEL + ":r600000"), any())).thenReturn(j2);

        Map<String, Object> r = service.reembedCourse(7L);

        verify(courseAccessService).assertManageCourse(7L);
        assertThat(r).containsEntry("model", MODEL).containsEntry("sourceCount", 2)
                .containsEntry("jobIds", List.of(1L, 2L));

        flags.setRagVectorEnabled(false);
        assertThatThrownBy(() -> service.reembedCourse(7L)).extracting("code").isEqualTo(404);
        flags.setRagVectorEnabled(true);
        flags.setRagEnabled(false);
        assertThatThrownBy(() -> service.reembedCourse(7L)).extracting("code").isEqualTo(404);
        flags.setRagEnabled(true);

        client.available = false;
        limits.getRag().setAllowHashingEmbedding(false);
        assertThatThrownBy(() -> service.reembedCourse(7L)).extracting("code").isEqualTo(503);

        doThrow(new BusinessException(com.wenjin.common.ResultCode.FORBIDDEN, "no"))
                .when(courseAccessService).assertManageCourse(8L);
        client.available = true;
        assertThatThrownBy(() -> service.reembedCourse(8L)).extracting("code").isEqualTo(403);
        CurrentUser.clear();
    }

    static final class FakeClient implements EmbeddingClient {
        final List<List<String>> batches = new ArrayList<>();
        boolean available = true;
        int failOnCall = -1;

        @Override
        public List<float[]> embed(List<String> texts) {
            if (batches.size() + 1 == failOnCall) {
                throw new BusinessException(com.wenjin.common.ResultCode.AI_ERROR, "down");
            }
            batches.add(List.copyOf(texts));
            return texts.stream().map(t -> new float[]{1, 0, 0, 0}).toList();
        }

        @Override
        public String modelId() {
            return MODEL;
        }

        @Override
        public boolean available() {
            return available;
        }
    }
}
