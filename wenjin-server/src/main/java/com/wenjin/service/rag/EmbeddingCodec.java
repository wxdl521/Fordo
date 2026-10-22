package com.wenjin.service.rag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * float[] ↔ byte[]（float32 little-endian），存 doc_chunk_embedding.vector。
 */
public final class EmbeddingCodec {

    private EmbeddingCodec() {
    }

    public static byte[] encode(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buf.asFloatBuffer().put(v);
        return buf.array();
    }

    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("向量字节长度非法: " + (bytes == null ? "null" : bytes.length));
        }
        float[] v = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);
        return v;
    }

    /** 余弦相似度（长度须相等；任一为零向量返回 0）。 */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("维度不一致: " + a.length + " vs " + b.length);
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / Math.sqrt(na * nb);
    }
}
