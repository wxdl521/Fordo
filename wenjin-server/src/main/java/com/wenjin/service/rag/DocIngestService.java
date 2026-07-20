package com.wenjin.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.entity.ChunkNodeMap;
import com.wenjin.entity.DocChunk;
import com.wenjin.entity.DocSource;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.ChunkNodeMapMapper;
import com.wenjin.mapper.DocChunkMapper;
import com.wenjin.mapper.DocSourceMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.support.ChunkMapReviewStatus;
import com.wenjin.support.DocSourceKind;
import com.wenjin.support.DocSourceStatus;
import com.wenjin.support.rag.NodeRefMarkdownParser;
import com.wenjin.support.rag.NodeRefMarkdownParser.NodeRefParseException;
import com.wenjin.support.rag.NodeRefMarkdownParser.ParsedNodeRef;
import com.wenjin.support.rag.NodeRefMarkdownParser.Section;
import com.wenjin.support.rag.TextChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DOC_INGEST 作业执行体：NODE_REF Markdown 全链路（解析 → 清障 → 切块 → 映射 APPROVED）。
 * <p>COURSEWARE/TEXTBOOK / PDF-PPT 初标：M8 后续；本类仅 NODE_REF。
 */
@Service
public class DocIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocIngestService.class);

    private final DocSourceMapper sourceMapper;
    private final DocChunkMapper chunkMapper;
    private final ChunkNodeMapMapper mapMapper;
    private final KgNodeMapper nodeMapper;

    public DocIngestService(DocSourceMapper sourceMapper,
                             DocChunkMapper chunkMapper,
                             ChunkNodeMapMapper mapMapper,
                             KgNodeMapper nodeMapper) {
        this.sourceMapper = Objects.requireNonNull(sourceMapper);
        this.chunkMapper = Objects.requireNonNull(chunkMapper);
        this.mapMapper = Objects.requireNonNull(mapMapper);
        this.nodeMapper = Objects.requireNonNull(nodeMapper);
    }

    /**
     * 执行 NODE_REF 入库。
     *
     * @param sourceId  已建 doc_source（INGESTING）
     * @param markdown  Markdown 正文
     * @return 结果摘要（写入 ai_job.result_json）
     */
    @Transactional
    public Map<String, Object> ingestNodeRef(long sourceId, String markdown) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", sourceId);
        result.put("kind", "NODE_REF");

        DocSource source = sourceMapper.selectById(sourceId);
        if (source == null) {
            result.put("error", "SOURCE_NOT_FOUND");
            return result;
        }
        if (source.getKind() == null || source.getKind() != DocSourceKind.NODE_REF) {
            fail(sourceId, result, "KIND_NOT_NODE_REF");
            return result;
        }

        // 半成品清障：重试时先清该 source 的 map + chunk
        clearSourceArtifacts(sourceId);

        Set<String> whitelist = loadNodeCodes(source.getCourseId());
        ParsedNodeRef parsed;
        try {
            parsed = NodeRefMarkdownParser.parse(markdown, whitelist);
        } catch (NodeRefParseException e) {
            fail(sourceId, result, "PARSE_FAILED");
            result.put("details", e.details());
            return result;
        }

        // 标题回写（frontmatter title 优先）
        if (StringUtils.hasText(parsed.title())
                && !Objects.equals(parsed.title(), source.getTitle())) {
            source.setTitle(parsed.title());
            sourceMapper.updateById(source);
        }

        int seq = 0;
        int chunkCount = 0;
        List<Long> chunkIds = new ArrayList<>();
        for (Section section : parsed.sections()) {
            String path = parsed.title() + ">" + section.heading();
            List<TextChunker.Chunk> pieces;
            if (section.content().length() > TextChunker.MAX) {
                pieces = TextChunker.chunk(section.content(), path);
            } else {
                pieces = List.of(new TextChunker.Chunk(section.content(), path));
            }
            for (TextChunker.Chunk piece : pieces) {
                DocChunk chunk = new DocChunk();
                chunk.setSourceId(sourceId);
                chunk.setSeq(seq++);
                chunk.setContent(piece.content());
                chunk.setCharCount(piece.charCount());
                chunk.setMetaJson(metaJson(path, section.heading()));
                chunkMapper.insert(chunk);
                chunkIds.add(chunk.getId());
                chunkCount++;

                ChunkNodeMap map = new ChunkNodeMap();
                map.setChunkId(chunk.getId());
                map.setCourseId(source.getCourseId());
                map.setNodeCode(parsed.nodeCode());
                map.setConfidence(new BigDecimal("1.000"));
                map.setReviewStatus(ChunkMapReviewStatus.APPROVED);
                map.setReviewedBy(source.getCreatedBy());
                map.setReviewedAt(LocalDateTime.now());
                mapMapper.insert(map);
            }
        }

        sourceMapper.updateStatus(sourceId, DocSourceStatus.READY);
        result.put("status", "READY");
        result.put("nodeCode", parsed.nodeCode());
        result.put("chunkCount", chunkCount);
        result.put("chunkIds", chunkIds);
        log.info("DOC_INGEST NODE_REF ready sourceId={} nodeCode={} chunks={}",
                sourceId, parsed.nodeCode(), chunkCount);
        return result;
    }

    /**
     * 从 payload 字节解码 UTF-8 Markdown 后入库。
     */
    @Transactional
    public Map<String, Object> ingestNodeRefBytes(long sourceId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sourceId", sourceId);
            fail(sourceId, result, "EMPTY_PAYLOAD");
            return result;
        }
        return ingestNodeRef(sourceId, new String(bytes, StandardCharsets.UTF_8));
    }

    void clearSourceArtifacts(long sourceId) {
        mapMapper.deleteBySourceId(sourceId);
        chunkMapper.deleteBySourceId(sourceId);
    }

    private Set<String> loadNodeCodes(Long courseId) {
        if (courseId == null) {
            return Set.of();
        }
        // QueryWrapper avoids Lambda cache requirements in pure unit tests
        List<KgNode> nodes = nodeMapper.selectList(new QueryWrapper<KgNode>()
                .eq("course_id", courseId)
                .isNull("archived_at")
                .select("node_code"));
        if (nodes == null || nodes.isEmpty()) {
            return Set.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (KgNode n : nodes) {
            if (n != null && StringUtils.hasText(n.getNodeCode())) {
                codes.add(n.getNodeCode());
            }
        }
        return codes;
    }

    private void fail(long sourceId, Map<String, Object> result, String errorCode) {
        try {
            sourceMapper.updateStatus(sourceId, DocSourceStatus.FAILED);
        } catch (Exception e) {
            log.warn("failed to mark source FAILED id={}: {}", sourceId, e.getMessage());
        }
        result.put("status", "FAILED");
        result.put("error", errorCode);
    }

    private static String metaJson(String path, String heading) {
        // keep under VARCHAR(500); simple JSON without ObjectMapper dependency
        String safePath = escapeJson(path == null ? "" : path);
        String safeHeading = escapeJson(heading == null ? "" : heading);
        String json = "{\"path\":\"" + safePath + "\",\"heading\":\"" + safeHeading + "\"}";
        if (json.length() > 500) {
            return json.substring(0, 500);
        }
        return json;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
