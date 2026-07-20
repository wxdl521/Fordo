package com.wenjin.service.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 单元测试用内存检索器：按子串/节点过滤模拟 FULLTEXT 命中。
 */
public final class InMemoryFulltextRetriever implements RetrieverPort {

    public record IndexedChunk(
            long courseId,
            long chunkId,
            String content,
            String sourceTitle,
            String metaJson,
            Set<String> approvedNodeCodes
    ) {
        public IndexedChunk {
            approvedNodeCodes = approvedNodeCodes == null
                    ? Set.of()
                    : Set.copyOf(approvedNodeCodes);
        }
    }

    private final List<IndexedChunk> index = new ArrayList<>();

    public InMemoryFulltextRetriever add(IndexedChunk chunk) {
        if (chunk != null) {
            index.add(chunk);
        }
        return this;
    }

    public InMemoryFulltextRetriever addAll(Collection<IndexedChunk> chunks) {
        if (chunks != null) {
            chunks.forEach(this::add);
        }
        return this;
    }

    @Override
    public List<RetrievalHit> retrieve(long courseId, List<String> nodeCodes, String query, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        Set<String> scope = new LinkedHashSet<>();
        if (nodeCodes != null) {
            for (String nc : nodeCodes) {
                if (StringUtils.hasText(nc)) {
                    scope.add(nc.trim());
                }
            }
        }
        List<RetrievalHit> hits = new ArrayList<>();
        for (IndexedChunk c : index) {
            if (c.courseId() != courseId) {
                continue;
            }
            if (!scope.isEmpty()) {
                boolean mapped = false;
                for (String nc : c.approvedNodeCodes()) {
                    if (scope.contains(nc)) {
                        mapped = true;
                        break;
                    }
                }
                if (!mapped) {
                    continue;
                }
            } else if (c.approvedNodeCodes().isEmpty()) {
                continue;
            }
            String content = c.content() == null ? "" : c.content();
            if (!content.toLowerCase(Locale.ROOT).contains(q) && !fuzzyContains(content, q)) {
                // simple char-overlap score for CJK: require at least half of query chars present
                if (!cjkLooseMatch(content, q)) {
                    continue;
                }
            }
            double score = score(content, q);
            if (score <= 0) {
                continue;
            }
            hits.add(new RetrievalHit(c.chunkId(), content, c.sourceTitle(), c.metaJson(), score));
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (hits.size() > topK) {
            return List.copyOf(hits.subList(0, topK));
        }
        return List.copyOf(hits);
    }

    private static boolean fuzzyContains(String content, String q) {
        return content.toLowerCase(Locale.ROOT).contains(q);
    }

    private static boolean cjkLooseMatch(String content, String q) {
        if (q.isEmpty()) {
            return false;
        }
        int hit = 0;
        for (int i = 0; i < q.length(); i++) {
            char ch = q.charAt(i);
            if (content.indexOf(ch) >= 0) {
                hit++;
            }
        }
        return hit * 2 >= q.length();
    }

    private static double score(String content, String q) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains(q)) {
            return 10.0 + Math.min(5.0, q.length() / 2.0);
        }
        int hit = 0;
        for (int i = 0; i < q.length(); i++) {
            if (content.indexOf(q.charAt(i)) >= 0) {
                hit++;
            }
        }
        return hit <= 0 ? 0.0 : (double) hit / q.length();
    }
}
