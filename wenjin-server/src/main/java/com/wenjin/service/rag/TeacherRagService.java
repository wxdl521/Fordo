package com.wenjin.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.BatchApproveMapsRequest;
import com.wenjin.dto.ChunkNodeMapPageVO;
import com.wenjin.dto.ChunkNodeMapVO;
import com.wenjin.dto.DocSourceVO;
import com.wenjin.dto.NodeRefIngestRequest;
import com.wenjin.entity.ChunkNodeMap;
import com.wenjin.entity.DocChunk;
import com.wenjin.entity.DocSource;
import com.wenjin.mapper.ChunkNodeMapMapper;
import com.wenjin.mapper.DocChunkMapper;
import com.wenjin.mapper.DocSourceMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.support.ChunkMapReviewStatus;
import com.wenjin.support.DocSourceKind;
import com.wenjin.support.DocSourceStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 教师端 RAG 语料审核：来源列表、映射通过/驳回、NODE_REF 同步入库（演示用）。
 * <p>{@code rag-enabled=false} 时统一 404。
 */
@Service
public class TeacherRagService {

    private static final int PREVIEW_MAX = 200;

    private final DocSourceMapper sourceMapper;
    private final DocChunkMapper chunkMapper;
    private final ChunkNodeMapMapper mapMapper;
    private final DocIngestService docIngestService;
    private final CourseAccessService courseAccessService;
    private final FeatureFlagsProperties featureFlags;

    public TeacherRagService(DocSourceMapper sourceMapper,
                             DocChunkMapper chunkMapper,
                             ChunkNodeMapMapper mapMapper,
                             DocIngestService docIngestService,
                             CourseAccessService courseAccessService,
                             FeatureFlagsProperties featureFlags) {
        this.sourceMapper = Objects.requireNonNull(sourceMapper);
        this.chunkMapper = Objects.requireNonNull(chunkMapper);
        this.mapMapper = Objects.requireNonNull(mapMapper);
        this.docIngestService = Objects.requireNonNull(docIngestService);
        this.courseAccessService = Objects.requireNonNull(courseAccessService);
        this.featureFlags = featureFlags;
    }

    public List<DocSourceVO> listSources(Long courseId) {
        assertRagEnabled();
        if (courseId == null || courseId <= 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        courseAccessService.assertManageCourse(courseId);

        List<DocSource> rows = sourceMapper.selectList(new QueryWrapper<DocSource>()
                .eq("course_id", courseId)
                .orderByDesc("id"));
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> sourceIds = rows.stream().map(DocSource::getId).filter(Objects::nonNull).toList();
        Map<Long, Long> chunkCounts = countChunksBySource(sourceIds);
        Map<Long, Long> pendingCounts = countPendingMapsBySource(sourceIds);

        List<DocSourceVO> out = new ArrayList<>(rows.size());
        for (DocSource s : rows) {
            DocSourceVO vo = new DocSourceVO();
            vo.setId(s.getId());
            vo.setCourseId(s.getCourseId());
            vo.setTitle(s.getTitle());
            vo.setKind(s.getKind());
            vo.setStatus(s.getStatus());
            vo.setCreatedAt(s.getCreatedAt());
            vo.setChunkCount(chunkCounts.getOrDefault(s.getId(), 0L));
            vo.setPendingMapCount(pendingCounts.getOrDefault(s.getId(), 0L));
            out.add(vo);
        }
        return out;
    }

    public ChunkNodeMapPageVO listMappings(Long courseId,
                                           String nodeCode,
                                           Integer status,
                                           int page,
                                           int size) {
        assertRagEnabled();
        if (courseId == null || courseId <= 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        courseAccessService.assertManageCourse(courseId);
        if (status != null && status != ChunkMapReviewStatus.PENDING
                && status != ChunkMapReviewStatus.APPROVED
                && status != ChunkMapReviewStatus.REJECTED) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无效审核状态");
        }

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;

        QueryWrapper<ChunkNodeMap> countQw = baseMapQuery(courseId, nodeCode, status);
        Long totalL = mapMapper.selectCount(countQw);
        long total = totalL == null ? 0L : totalL;

        List<ChunkNodeMap> maps = List.of();
        if (total > 0 && offset < total) {
            QueryWrapper<ChunkNodeMap> listQw = baseMapQuery(courseId, nodeCode, status)
                    .orderByDesc("id")
                    .last("LIMIT " + offset + ", " + safeSize);
            List<ChunkNodeMap> fetched = mapMapper.selectList(listQw);
            maps = fetched == null ? List.of() : fetched;
        }

        Set<Long> chunkIds = maps.stream()
                .map(ChunkNodeMap::getChunkId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, DocChunk> chunks = loadChunks(chunkIds);
        Map<Long, DocSource> sources = loadSourcesForChunks(chunks.values());

        List<ChunkNodeMapVO> items = new ArrayList<>(maps.size());
        for (ChunkNodeMap m : maps) {
            items.add(toMapVo(m, chunks.get(m.getChunkId()), sources));
        }

        ChunkNodeMapPageVO pageVo = new ChunkNodeMapPageVO();
        pageVo.setTotal(total);
        pageVo.setPage(safePage);
        pageVo.setSize(safeSize);
        pageVo.setItems(items);
        return pageVo;
    }

    private static QueryWrapper<ChunkNodeMap> baseMapQuery(Long courseId, String nodeCode, Integer status) {
        QueryWrapper<ChunkNodeMap> qw = new QueryWrapper<ChunkNodeMap>()
                .eq("course_id", courseId);
        if (status != null) {
            qw.eq("review_status", status);
        }
        if (StringUtils.hasText(nodeCode)) {
            qw.eq("node_code", nodeCode.trim());
        }
        return qw;
    }

    @Transactional
    public void approve(Long id) {
        reviewOne(id, ChunkMapReviewStatus.APPROVED);
    }

    @Transactional
    public void reject(Long id) {
        reviewOne(id, ChunkMapReviewStatus.REJECTED);
    }

    @Transactional
    public int batchApprove(Long courseId, BatchApproveMapsRequest req) {
        assertRagEnabled();
        if (courseId == null || courseId <= 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        courseAccessService.assertManageCourse(courseId);
        long teacherId = CurrentUser.requireTeacher().userId();

        QueryWrapper<ChunkNodeMap> qw = new QueryWrapper<ChunkNodeMap>()
                .eq("course_id", courseId)
                .eq("review_status", ChunkMapReviewStatus.PENDING);
        if (req != null && StringUtils.hasText(req.getNodeCode())) {
            qw.eq("node_code", req.getNodeCode().trim());
        }
        if (req != null && req.getMinConfidence() != null) {
            qw.ge("confidence", req.getMinConfidence());
        }

        List<ChunkNodeMap> pending = mapMapper.selectList(qw);
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (ChunkNodeMap m : pending) {
            m.setReviewStatus(ChunkMapReviewStatus.APPROVED);
            m.setReviewedBy(teacherId);
            m.setReviewedAt(now);
            mapMapper.updateById(m);
            n++;
        }
        return n;
    }

    /**
     * 创建 NODE_REF 来源并同步入库（UI 演示；生产 PDF 走 AiJob）。
     */
    @Transactional
    public DocSourceVO createNodeRef(NodeRefIngestRequest req) {
        assertRagEnabled();
        if (req == null || req.getCourseId() == null || req.getCourseId() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseId required");
        }
        if (!StringUtils.hasText(req.getMarkdown())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "markdown required");
        }
        courseAccessService.assertManageCourse(req.getCourseId());
        long teacherId = CurrentUser.requireTeacher().userId();

        String title = StringUtils.hasText(req.getTitle())
                ? req.getTitle().trim()
                : "NODE_REF 导入";

        DocSource source = new DocSource();
        source.setCourseId(req.getCourseId());
        source.setTitle(title);
        source.setKind(DocSourceKind.NODE_REF);
        source.setStatus(DocSourceStatus.INGESTING);
        source.setCreatedBy(teacherId);
        source.setCreatedAt(LocalDateTime.now());
        sourceMapper.insert(source);

        Map<String, Object> result = docIngestService.ingestNodeRef(source.getId(), req.getMarkdown());
        // re-read after ingest (title/status may change)
        DocSource refreshed = sourceMapper.selectById(source.getId());
        if (refreshed == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "入库后未找到语料来源");
        }
        if (result != null && "FAILED".equals(String.valueOf(result.get("status")))) {
            // still return VO so UI can show FAILED; do not throw unless parse hard-fail desired
            DocSourceVO failed = toSourceVo(refreshed, 0L, 0L);
            return failed;
        }

        long chunks = chunkMapper.selectCount(new QueryWrapper<DocChunk>().eq("source_id", refreshed.getId()));
        long pending = countPendingMapsBySource(List.of(refreshed.getId()))
                .getOrDefault(refreshed.getId(), 0L);
        return toSourceVo(refreshed, chunks, pending);
    }

    private void reviewOne(Long id, int targetStatus) {
        assertRagEnabled();
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "映射不存在");
        }
        ChunkNodeMap row = mapMapper.selectById(id);
        if (row == null || row.getCourseId() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "映射不存在");
        }
        courseAccessService.assertManageCourse(row.getCourseId());
        long teacherId = CurrentUser.requireTeacher().userId();
        row.setReviewStatus(targetStatus);
        row.setReviewedBy(teacherId);
        row.setReviewedAt(LocalDateTime.now());
        mapMapper.updateById(row);
    }

    private void assertRagEnabled() {
        if (featureFlags == null || !featureFlags.isRagEnabled()) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
    }

    private Map<Long, Long> countChunksBySource(List<Long> sourceIds) {
        Map<Long, Long> out = new HashMap<>();
        if (sourceIds == null || sourceIds.isEmpty()) {
            return out;
        }
        for (Long sid : sourceIds) {
            Long c = chunkMapper.selectCount(new QueryWrapper<DocChunk>().eq("source_id", sid));
            out.put(sid, c == null ? 0L : c);
        }
        return out;
    }

    private Map<Long, Long> countPendingMapsBySource(List<Long> sourceIds) {
        Map<Long, Long> out = new HashMap<>();
        if (sourceIds == null || sourceIds.isEmpty()) {
            return out;
        }
        for (Long sid : sourceIds) {
            // maps for chunks under this source with PENDING
            List<DocChunk> chunks = chunkMapper.selectList(new QueryWrapper<DocChunk>()
                    .eq("source_id", sid)
                    .select("id"));
            if (chunks == null || chunks.isEmpty()) {
                out.put(sid, 0L);
                continue;
            }
            List<Long> cids = chunks.stream().map(DocChunk::getId).filter(Objects::nonNull).toList();
            if (cids.isEmpty()) {
                out.put(sid, 0L);
                continue;
            }
            Long pending = mapMapper.selectCount(new QueryWrapper<ChunkNodeMap>()
                    .in("chunk_id", cids)
                    .eq("review_status", ChunkMapReviewStatus.PENDING));
            out.put(sid, pending == null ? 0L : pending);
        }
        return out;
    }

    private Map<Long, DocChunk> loadChunks(Set<Long> chunkIds) {
        Map<Long, DocChunk> out = new HashMap<>();
        if (chunkIds == null || chunkIds.isEmpty()) {
            return out;
        }
        List<DocChunk> list = chunkMapper.selectBatchIds(chunkIds);
        if (list != null) {
            for (DocChunk c : list) {
                if (c != null && c.getId() != null) {
                    out.put(c.getId(), c);
                }
            }
        }
        return out;
    }

    private Map<Long, DocSource> loadSourcesForChunks(Iterable<DocChunk> chunks) {
        Set<Long> sourceIds = new HashSet<>();
        if (chunks != null) {
            for (DocChunk c : chunks) {
                if (c != null && c.getSourceId() != null) {
                    sourceIds.add(c.getSourceId());
                }
            }
        }
        Map<Long, DocSource> out = new HashMap<>();
        if (sourceIds.isEmpty()) {
            return out;
        }
        List<DocSource> list = sourceMapper.selectBatchIds(sourceIds);
        if (list != null) {
            for (DocSource s : list) {
                if (s != null && s.getId() != null) {
                    out.put(s.getId(), s);
                }
            }
        }
        return out;
    }

    private static ChunkNodeMapVO toMapVo(ChunkNodeMap m, DocChunk chunk, Map<Long, DocSource> sources) {
        ChunkNodeMapVO vo = new ChunkNodeMapVO();
        vo.setId(m.getId());
        vo.setChunkId(m.getChunkId());
        vo.setCourseId(m.getCourseId());
        vo.setNodeCode(m.getNodeCode());
        vo.setConfidence(m.getConfidence() == null ? BigDecimal.ZERO : m.getConfidence());
        vo.setReviewStatus(m.getReviewStatus());
        vo.setReviewedAt(m.getReviewedAt());
        if (chunk != null) {
            vo.setChunkPreview(preview(chunk.getContent()));
            DocSource src = chunk.getSourceId() == null ? null : sources.get(chunk.getSourceId());
            vo.setSourceTitle(src == null ? null : src.getTitle());
        }
        return vo;
    }

    private static DocSourceVO toSourceVo(DocSource s, long chunks, long pending) {
        DocSourceVO vo = new DocSourceVO();
        vo.setId(s.getId());
        vo.setCourseId(s.getCourseId());
        vo.setTitle(s.getTitle());
        vo.setKind(s.getKind());
        vo.setStatus(s.getStatus());
        vo.setCreatedAt(s.getCreatedAt());
        vo.setChunkCount(chunks);
        vo.setPendingMapCount(pending);
        return vo;
    }

    private static String preview(String content) {
        if (content == null) {
            return "";
        }
        String t = content.replaceAll("\\s+", " ").trim();
        if (t.length() <= PREVIEW_MAX) {
            return t;
        }
        return t.substring(0, PREVIEW_MAX) + "…";
    }
}
