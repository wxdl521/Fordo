package com.wenjin.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线确定性嵌入：字符 1/2-gram 哈希到 dim 维（带符号）+ L2 归一化。
 * 仅用于测试与无 key 演示；不是 Spring bean（是否启用由 R2 的开关决定）。
 */
public class HashingEmbeddingClient implements EmbeddingClient {

    private final int dim;

    public HashingEmbeddingClient(int dim) {
        if (dim < 1) {
            throw new IllegalArgumentException("dim must be >= 1");
        }
        this.dim = dim;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(embedOne(t == null ? "" : t));
        }
        return out;
    }

    @Override
    public String modelId() {
        return "hash-ngram@" + dim;
    }

    @Override
    public boolean available() {
        return true;
    }

    float[] embedOne(String text) {
        float[] v = new float[dim];
        String s = text.toLowerCase(java.util.Locale.ROOT);
        int[] cps = s.codePoints().filter(c -> !Character.isWhitespace(c)).toArray();
        for (int i = 0; i < cps.length; i++) {
            add(v, mix(cps[i], 0x9E3779B1));
            if (i + 1 < cps.length) {
                add(v, mix(cps[i] * 31 + cps[i + 1], 0x85EBCA6B));
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        if (norm > 0) {
            float inv = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < dim; i++) {
                v[i] *= inv;
            }
        }
        return v;
    }

    private void add(float[] v, int h) {
        int idx = Math.floorMod(h, dim);
        v[idx] += ((h >>> 31) == 0) ? 1f : -1f;
    }

    private static int mix(int x, int seed) {
        int h = x * seed;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return h;
    }
}
