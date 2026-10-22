package com.wenjin.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.support.DocumentTextExtractor;
import com.wenjin.support.rag.ChunkNodeLabeler;
import com.wenjin.support.rag.PlainDocumentSplitter;
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

    private final DocumentTextExtractor extractor;

    public DocIngestService(DocSourceMapper sourceMapper,
                             DocChunkMapper chunkMapper,
                             ChunkNodeMapMapper mapMapper,
                             KgNodeMapper nodeMapper,
                             DocumentTextExtractor extractor) {
        this.sourceMapper = Objects.requireNonNull(sourceMapper);
        this.chunkMapper = Objects.requireNonNull(chunkMapper);
        this.mapMapper = Objects.requireNonNull(mapMapper);
        this.nodeMapper = Objects.requireNonNull(nodeMapper);
        this.extractor = Objects.requireNonNull(extractor);
    }

    /** 作业入口：按 source.kind 分派（NODE_REF → 原路径；其余 → 文件入库 + 自动初标）。 */
    @Transactional
    public Map<String, Object> ingestSource(long sourceId, byte[] bytes, String filename) {
        DocSource source = sourceMapper.selectById(sourceId);
        if (source != null && source.getKind() != null && source.getKind() == DocSourceKind.NODE_REF) {
            return ingestNodeRefBytes(sourceId, bytes);
        }
        return ingestDocument(sourceId, bytes, filename);
    }

    /** 作业异常时由 worker 调用，把来源置 FAILED（独立事务，外层已回滚）。 */
    @Transactional
    public void markFailed(long sourceId) {
        if (sourceId > 0) {
            sourceMapper.updateStatus(sourceId, DocSourceStatus.FAILED);
        }
    }

    /**
     * COURSEWARE/TEXTBOOK 文件入库：抽取文本 → 带标题路径切块 → 自动初标（PENDING 映射）。
     * md/txt 带 NODE_REF frontmatter 时转走原 NODE_REF 路径。
     */
    @Transactional
    public Map<String, Object> ingestDocument(long sourceId, byte[] bytes, String filename) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", sourceId);
        DocSource source = sourceMapper.selectById(sourceId);
        if (source == null) {
            result.put("error", "SOURCE_NOT_FOUND");
            return result;
        }
        if (bytes == null || bytes.length == 0) {
            fail(sourceId, result, "EMPTY_PAYLOAD");
            return result;
        }
        String ext = DocumentTextExtractor.extension(filename);
        boolean plain = ext.equals("md") || ext.equals("markdown") || ext.equals("txt");
        String text;
        try {
            if (plain) {
                text = extractor.decodePlainText(bytes);
            } else if (ext.equals("pdf") || ext.equals("docx")) {
                text = extractor.extract(bytes, ext);
            } else {
                fail(sourceId, result, "UNSUPPORTED_TYPE");
                return result;
            }
        } catch (BusinessException e) {
            fail(sourceId, result, "EXTRACT_FAILED");
            result.put("message", e.getMessage());
            return result;
        }

        if (plain && NodeRefMarkdownParser.hasNodeRefFrontmatter(text)) {
            if (source.getKind() == null || source.getKind() != DocSourceKind.NODE_REF) {
                source.setKind(DocSourceKind.NODE_REF);
                sourceMapper.updateById(source);
            }
            return ingestNodeRef(sourceId, text);
        }

        result.put("kind", source.getKind() != null && source.getKind() == DocSourceKind.TEXTBOOK
                ? "TEXTBOOK" : "COURSEWARE");
        clearSourceArtifacts(sourceId);
        List<PlainDocumentSplitter.Piece> pieces = PlainDocumentSplitter.split(text, source.getTitle());
        if (pieces.isEmpty()) {
            fail(sourceId, result, "EMPTY_CONTENT");
            return result;
        }
        ChunkNodeLabeler labeler = new ChunkNodeLabeler(loadNodeCandidates(source.getCourseId()));

        int seq = 0;
        int labeled = 0;
        int mappings = 0;
        List<Long> chunkIds = new ArrayList<>();
        for (PlainDocumentSplitter.Piece piece : pieces) {
            DocChunk chunk = new DocChunk();
            chunk.setSourceId(sourceId);
            chunk.setSeq(seq++);
            chunk.setContent(piece.content());
            chunk.setCharCount(piece.content().length());
            chunk.setMetaJson(metaJson(piece.path(), piece.heading()));
            chunkMapper.insert(chunk);
            chunkIds.add(chunk.getId());

            List<ChunkNodeLabeler.Label> labels = labeler.label(piece.content(), piece.path());
            if (!labels.isEmpty()) {
                labeled++;
            }
            for (ChunkNodeLabeler.Label label : labels) {
                ChunkNodeMap map = new ChunkNodeMap();
                map.setChunkId(chunk.getId());
                map.setCourseId(source.getCourseId());
                map.setNodeCode(label.nodeCode());
                map.setConfidence(label.confidence());
                map.setReviewStatus(ChunkMapReviewStatus.PENDING);
                mapMapper.insert(map);
                mappings++;
            }
        }
        sourceMapper.updateStatus(sourceId, DocSourceStatus.READY);
        result.put("status", "READY");
        result.put("chunkCount", chunkIds.size());
        result.put("labeledChunks", labeled);
        result.put("unlabeledChunks", chunkIds.size() - labeled);
        result.put("mappingCount", mappings);
        result.put("chunkIds", chunkIds);
        log.info("DOC_INGEST document ready sourceId={} chunks={} labeled={}",
                sourceId, chunkIds.size(), labeled);
        return result;
    }

    private List<ChunkNodeLabeler.NodeCandidate> loadNodeCandidates(Long courseId) {
        if (courseId == null) {
            return List.of();
        }
        List<KgNode> nodes = nodeMapper.selectList(new QueryWrapper<KgNode>()
                .eq("course_id", courseId)
                .isNull("archived_at")
                .select("node_code", "name"));
        List<ChunkNodeLabeler.NodeCandidate> out = new ArrayList<>();
        if (nodes != null) {
            for (KgNode n : nodes) {
                if (n != null) {
                    out.add(new ChunkNodeLabeler.NodeCandidate(n.getNodeCode(), n.getName()));
                }
            }
        }
        return out;
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

    private static final int META_MAX = 500;
    private static final ObjectMapper META_MAPPER = new ObjectMapper();

    /** 先构造对象再按字段截断，保证落库的 meta_json 恒为合法 JSON 且 ≤ 500 字符。 */
    static String metaJson(String path, String heading) {
        String p = path == null ? "" : path;
        String h = heading == null ? "" : heading;
        while (true) {
            String json = toMetaJson(p, h);
            if (json.length() <= META_MAX || (p.isEmpty() && h.isEmpty())) {
                return json;
            }
            if (p.length() >= h.length()) {
                p = shrink(p);
            } else {
                h = shrink(h);
            }
        }
    }

    private static String toMetaJson(String path, String heading) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("heading", heading);
        try {
            return META_MAPPER.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String shrink(String s) {
        int len = (int) (s.length() * 0.8);
        if (len > 0 && Character.isHighSurrogate(s.charAt(len - 1))) {
            len--;
        }
        return s.substring(0, Math.max(0, len));
    }
}
