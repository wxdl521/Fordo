package com.wenjin.service.rag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 单课程单模型的内存向量索引（R2）：chunkIds + 已 L2 归一化的 float[][] + 每块的 APPROVED 节点集合。
 * <p>只收录「有该模型向量 且 有 APPROVED 映射 且 来源 READY」的块，与全文检索的可检索语义一致。暴力余弦检索。
 */
public final class CourseVectorIndex {

    /** 一条索引项。 */
    public record Entry(long chunkId, float[] vector, Set<String> nodeCodes) {
        public Entry {
            nodeCodes = nodeCodes == null ? Set.of() : Set.copyOf(nodeCodes);
        }
    }

    /** 一条打分结果。 */
    public record Scored(long chunkId, double score) {
    }

    private final String modelId;
    private final long[] chunkIds;
    private final float[][] vectors;
    private final List<Set<String>> nodes;

    public CourseVectorIndex(String modelId, List<Entry> entries) {
        this.modelId = modelId;
        int n = entries == null ? 0 : entries.size();
        this.chunkIds = new long[n];
        this.vectors = new float[n][];
        this.nodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Entry e = entries.get(i);
            chunkIds[i] = e.chunkId();
            vectors[i] = normalize(e.vector());
            nodes.add(e.nodeCodes());
        }
    }

    public String modelId() {
        return modelId;
    }

    public int size() {
        return chunkIds.length;
    }

    public boolean isEmpty() {
        return chunkIds.length == 0;
    }

    /**
     * @param query     查询向量（任意范数）
     * @param nodeScope 节点圈定；null/空 = 全课程
     * @param minScore  最低余弦
     * @param limit     最多返回条数
     */
    public List<Scored> search(float[] query, Collection<String> nodeScope, double minScore, int limit) {
        if (query == null || limit <= 0 || isEmpty()) {
            return List.of();
        }
        float[] q = normalize(query);
        boolean scoped = nodeScope != null && !nodeScope.isEmpty();
        List<Scored> out = new ArrayList<>();
        for (int i = 0; i < chunkIds.length; i++) {
            float[] v = vectors[i];
            if (v.length != q.length) {
                continue;
            }
            if (scoped && !intersects(nodes.get(i), nodeScope)) {
                continue;
            }
            double dot = 0;
            for (int d = 0; d < v.length; d++) {
                dot += (double) v[d] * q[d];
            }
            if (dot >= minScore) {
                out.add(new Scored(chunkIds[i], dot));
            }
        }
        out.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingLong(Scored::chunkId));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : List.copyOf(out);
    }

    private static boolean intersects(Set<String> chunkNodes, Collection<String> scope) {
        for (String nc : scope) {
            if (chunkNodes.contains(nc)) {
                return true;
            }
        }
        return false;
    }

    static float[] normalize(float[] v) {
        float[] out = new float[v == null ? 0 : v.length];
        if (v == null) {
            return out;
        }
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        if (norm == 0) {
            return out;
        }
        double inv = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] * inv);
        }
        return out;
    }
}
