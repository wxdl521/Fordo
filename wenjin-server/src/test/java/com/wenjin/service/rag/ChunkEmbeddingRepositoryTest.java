package com.wenjin.service.rag;

import com.wenjin.entity.DocChunkEmbedding;
import com.wenjin.mapper.DocChunkEmbeddingMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkEmbeddingRepositoryTest {

    private final DocChunkEmbeddingMapper mapper = mock(DocChunkEmbeddingMapper.class);
    private final ChunkEmbeddingRepository repo = new ChunkEmbeddingRepository(mapper);

    private static DocChunkEmbedding row(long id, float[] v, int dim, String sha) {
        DocChunkEmbedding e = new DocChunkEmbedding();
        e.setChunkId(id);
        e.setVector(EmbeddingCodec.encode(v));
        e.setDim(dim);
        e.setContentSha(sha);
        return e;
    }

    @Test
    void upsert_encodesVectorAndDim() {
        repo.upsert(7L, "m@2", new float[]{1f, 2f}, "sha");
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(mapper).upsert(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("m@2"),
                org.mockito.ArgumentMatchers.eq(2), bytes.capture(), org.mockito.ArgumentMatchers.eq("sha"));
        assertThat(EmbeddingCodec.decode(bytes.getValue())).containsExactly(1f, 2f);
    }

    @Test
    void load_decodes_andSkipsRowsWhoseDimDisagrees() {
        when(mapper.selectByCourseAndModel(3L, "m@2")).thenReturn(List.of(
                row(1, new float[]{1f, 0f}, 2, "a"),
                row(2, new float[]{1f, 0f}, 3, "bad")));
        List<ChunkEmbeddingRepository.StoredEmbedding> out = repo.loadByCourseAndModel(3L, "m@2");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).chunkId()).isEqualTo(1L);
        assertThat(out.get(0).vector()).containsExactly(1f, 0f);
        assertThat(out.get(0).contentSha()).isEqualTo("a");
    }

    @Test
    void shaLookup_andDelete_shortCircuitOnEmpty() {
        when(mapper.selectShaByChunkIds(org.mockito.ArgumentMatchers.eq("m"), anyCollection()))
                .thenReturn(List.of(row(5, new float[0], 0, "s5")));
        assertThat(repo.loadShaByChunkIds("m", List.of(5L, 6L))).isEqualTo(Map.of(5L, "s5"));
        assertThat(repo.loadShaByChunkIds("m", List.of())).isEmpty();
        assertThat(repo.deleteByChunkIds(List.of())).isZero();
        verify(mapper, never()).deleteByChunkIds(any());
        repo.deleteByChunkIds(List.of(1L));
        verify(mapper).deleteByChunkIds(List.of(1L));
    }

    @Test
    void contentSha_isSha256Hex() {
        assertThat(ChunkEmbeddingRepository.contentSha("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
