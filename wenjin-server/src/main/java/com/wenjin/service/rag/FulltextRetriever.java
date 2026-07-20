package com.wenjin.service.rag;

import com.wenjin.mapper.DocChunkMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MySQL FULLTEXT ngram 检索实现（{@link RetrieverPort}）。
 */
@Component
public class FulltextRetriever implements RetrieverPort {

    private final DocChunkMapper chunkMapper;

    public FulltextRetriever(DocChunkMapper chunkMapper) {
        this.chunkMapper = Objects.requireNonNull(chunkMapper, "chunkMapper");
    }

    @Override
    public List<RetrievalHit> retrieve(long courseId, List<String> nodeCodes, String query, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        List<String> nodes = nodeCodes == null || nodeCodes.isEmpty() ? null : List.copyOf(nodeCodes);
        List<Map<String, Object>> rows = chunkMapper.matchAgainst(courseId, query.trim(), nodes, topK);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<RetrievalHit> hits = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            long chunkId = toLong(row.get("chunk_id"));
            String content = toStr(row.get("content"));
            if (chunkId <= 0 || !StringUtils.hasText(content)) {
                continue;
            }
            hits.add(new RetrievalHit(
                    chunkId,
                    content,
                    toStr(row.get("source_title")),
                    toStr(row.get("meta_json")),
                    toDouble(row.get("score"))));
        }
        return List.copyOf(hits);
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String toStr(Object v) {
        return v == null ? null : v.toString();
    }
}
