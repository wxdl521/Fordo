package com.wenjin.service.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingCodecTest {

    @Test
    void roundTrip_isLossless() {
        float[] v = {0f, -1.5f, 3.14159f, Float.MIN_VALUE, Float.MAX_VALUE, 1e-20f};
        byte[] b = EmbeddingCodec.encode(v);
        assertThat(b).hasSize(v.length * 4);
        assertThat(EmbeddingCodec.decode(b)).containsExactly(v);
    }

    @Test
    void littleEndianLayout() {
        // 1.0f = 0x3F800000 → LE bytes 00 00 80 3F
        assertThat(EmbeddingCodec.encode(new float[]{1.0f})).containsExactly(0, 0, (byte) 0x80, 0x3F);
    }

    @Test
    void decode_rejectsBadLength() {
        assertThatThrownBy(() -> EmbeddingCodec.decode(new byte[7])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingCodec.decode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cosine_basics() {
        assertThat(EmbeddingCodec.cosine(new float[]{1, 0}, new float[]{1, 0})).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(EmbeddingCodec.cosine(new float[]{1, 0}, new float[]{0, 1})).isZero();
        assertThat(EmbeddingCodec.cosine(new float[]{0, 0}, new float[]{0, 1})).isZero();
        assertThatThrownBy(() -> EmbeddingCodec.cosine(new float[]{1}, new float[]{1, 2}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
