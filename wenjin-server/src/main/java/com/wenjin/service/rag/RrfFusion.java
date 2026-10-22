package com.wenjin.service.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion（R2）：score(d) = Σ_lists 1 / (k + rank_list(d))，rank 从 1 起。
 * <p>同分时按「各列表中的最好名次」升序，再按首次出现的列表（全文在前）、chunkId 升序，保证确定性。
 * 命中正文取首次出现的那条（全文优先）；返回命中的 {@code score} 为融合分。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    public static List<RetrievalHit> fuse(List<List<RetrievalHit>> rankedLists, int k, int topK) {
        if (rankedLists == null || rankedLists.isEmpty() || topK <= 0) {
            return List.of();
        }
        int kk = Math.max(0, k);
        Map<Long, Acc> acc = new LinkedHashMap<>();
        for (int li = 0; li < rankedLists.size(); li++) {
            List<RetrievalHit> list = rankedLists.get(li);
            if (list == null) {
                continue;
            }
            int rank = 0;
            for (RetrievalHit hit : list) {
                if (hit == null) {
                    continue;
                }
                Acc a = acc.get(hit.chunkId());
                if (a != null && a.lastList == li) {
                    continue; // 同一列表内重复 chunk 只计首个名次
                }
                rank++;
                if (a == null) {
                    a = new Acc(hit, li);
                    acc.put(hit.chunkId(), a);
                }
                a.lastList = li;
                a.score += 1.0 / (kk + rank);
                a.bestRank = Math.min(a.bestRank, rank);
            }
        }
        List<Acc> sorted = new ArrayList<>(acc.values());
        sorted.sort(Comparator.comparingDouble((Acc a) -> a.score).reversed()
                .thenComparingInt(a -> a.bestRank)
                .thenComparingInt(a -> a.firstList)
                .thenComparingLong(a -> a.hit.chunkId()));
        List<RetrievalHit> out = new ArrayList<>(Math.min(topK, sorted.size()));
        for (Acc a : sorted) {
            if (out.size() >= topK) {
                break;
            }
            RetrievalHit h = a.hit;
            out.add(new RetrievalHit(h.chunkId(), h.content(), h.sourceTitle(), h.metaJson(), a.score));
        }
        return List.copyOf(out);
    }

    private static final class Acc {
        final RetrievalHit hit;
        final int firstList;
        int lastList = -1;
        int bestRank = Integer.MAX_VALUE;
        double score;

        Acc(RetrievalHit hit, int firstList) {
            this.hit = hit;
            this.firstList = firstList;
        }
    }
}
