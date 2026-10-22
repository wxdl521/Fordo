package com.wenjin.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.ai.EmbeddingClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.AiJobVO;
import com.wenjin.entity.DocChunk;
import com.wenjin.entity.DocSource;
import com.wenjin.mapper.DocChunkMapper;
import com.wenjin.mapper.DocSourceMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.AiJobService;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.DocSourceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;

/**
 * RAG 嵌入作业（R2）：派发（入库后自动 / 教师「重建向量」）与执行（RAG_EMBED 作业体）。
 *
 * <p>幂等键（≤ 64 字符，超长时 modelId 换成其 SHA-256 前 16 位）：
 * <ul>
 *   <li>入库后自动：{@code emb:{sourceId}:{modelId}:{minChunkId}}——同一次入库重复派发命中原作业；
 *       重新入库产生新 chunk 时得到新作业；</li>
 *   <li>重建向量：{@code emb:{sourceId}:{modelId}:r{epochMinute}}——同一分钟内重复点击幂等，之后可再次回填。</li>
 * </ul>
 * 执行时按 {@code content_sha} 跳过该模型下内容未变的块；向量模式关时作业以 SKIPPED 成功结束。
 */
@Service
public class RagEmbedService {

    private static final Logger log = LoggerFactory.getLogger(RagEmbedService.class);

    /** 单次嵌入调用的块数（客户端内部还会按 batch-size 再分批）；批间上报进度并续租。 */
    static final int EMBED_BATCH = 32;
    static final int MAX_KEY = 64;

    private final ActiveEmbeddingResolver resolver;
    private final DocSourceMapper sourceMapper;
    private final DocChunkMapper chunkMapper;
    private final ChunkEmbeddingRepository embeddingRepository;
    private final AiJobService aiJobService;
    private final CourseAccessService courseAccessService;
    private final FeatureFlagsProperties featureFlags;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public RagEmbedService(ActiveEmbeddingResolver resolver,
                           DocSourceMapper sourceMapper,
                           DocChunkMapper chunkMapper,
                           ChunkEmbeddingRepository embeddingRepository,
                           AiJobService aiJobService,
                           CourseAccessService courseAccessService,
                           FeatureFlagsProperties featureFlags,
                           ApplicationEventPublisher events,
                           Clock clock) {
        this.resolver = Objects.requireNonNull(resolver);
        this.sourceMapper = sourceMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingRepository = embeddingRepository;
        this.aiJobService = aiJobService;
        this.courseAccessService = courseAccessService;
        this.featureFlags = featureFlags;
        this.events = events;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** 当前生效模型；向量模式关时为空。 */
    public Optional<String> activeModelId() {
        return resolver.resolve().map(EmbeddingClient::modelId);
    }

    /** 某来源在指定模型下已嵌入块数。 */
    public long embeddedCount(long sourceId, String modelId) {
        return embeddingRepository.countBySourceAndModel(sourceId, modelId);
    }

    // ── 派发 ─────────────────────────────────────────────────────────────

    /**
     * 入库成功（事务提交后）自动派发；向量模式关 / 无属主 / 总闸关时返回 null。独立事务，失败由调用方吞掉。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiJobVO dispatchAfterIngest(RagSourceIngestedEvent event) {
        if (event == null || event.ownerId() == null || event.sourceId() <= 0) {
            return null;
        }
        Optional<EmbeddingClient> client = resolver.resolve();
        if (client.isEmpty()) {
            return null;
        }
        String modelId = client.get().modelId();
        String key = autoKey(event.sourceId(), modelId, event.minChunkId());
        AiJobVO job = aiJobService.enqueueInternal(event.courseId(), event.ownerId(), AiJobKind.RAG_EMBED, key,
                body(event.sourceId(), modelId));
        log.info("RAG_EMBED dispatched after ingest sourceId={} model={} jobId={}",
                event.sourceId(), modelId, job == null ? null : job.getId());
        return job;
    }

    /**
     * 教师「重建向量」：对课程全部 READY 来源按 source 派发 RAG_EMBED（内容未变的块执行时跳过）。
     * rag / rag-vector 开关关 → 404；无可用嵌入客户端 → 503。
     */
    public Map<String, Object> reembedCourse(Long courseId) {
        if (featureFlags == null || !featureFlags.isRagEnabled() || !featureFlags.isRagVectorEnabled()) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        if (courseId == null || courseId <= 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        courseAccessService.assertManageCourse(courseId);
        EmbeddingClient client = resolver.resolve().orElseThrow(() -> new BusinessException(
                ResultCode.SERVICE_UNAVAILABLE, "未配置可用的嵌入模型，无法重建向量"));
        String modelId = client.modelId();
        long minute = clock.millis() / 60_000L;
        List<DocSource> sources = sourceMapper.selectList(new QueryWrapper<DocSource>()
                .eq("course_id", courseId)
                .eq("status", DocSourceStatus.READY)
                .orderByAsc("id"));
        List<Long> jobIds = new ArrayList<>();
        if (sources != null) {
            for (DocSource s : sources) {
                AiJobVO job = aiJobService.dispatch(courseId, AiJobKind.RAG_EMBED,
                        reembedKey(s.getId(), modelId, minute), body(s.getId(), modelId));
                if (job != null && job.getId() != null) {
                    jobIds.add(job.getId());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", modelId);
        out.put("sourceCount", sources == null ? 0 : sources.size());
        out.put("jobIds", jobIds);
        return out;
    }

    // ── 执行 ─────────────────────────────────────────────────────────────

    /**
     * RAG_EMBED 作业体：对来源的块分批嵌入并 upsert（content_sha 未变跳过）。
     *
     * @param sourceId         来源
     * @param expectedCourseId 作业所属课程（来源须属于该课程）；null 不校验
     * @param progress         进度回调（10–90），worker 借此续租 / 检测租约丢失
     */
    public Map<String, Object> embedSource(long sourceId, Long expectedCourseId, IntConsumer progress) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", sourceId);
        Optional<EmbeddingClient> resolved = resolver.resolve();
        if (resolved.isEmpty()) {
            result.put("status", "SKIPPED");
            result.put("reason", "VECTOR_OFF");
            return result;
        }
        EmbeddingClient client = resolved.get();
        String modelId = client.modelId();
        result.put("model", modelId);
        DocSource source = sourceMapper.selectById(sourceId);
        if (source == null || (expectedCourseId != null && !Objects.equals(source.getCourseId(), expectedCourseId))) {
            throw new BusinessException(ResultCode.NOT_FOUND, "语料来源不存在");
        }
        if (source.getStatus() == null || source.getStatus() != DocSourceStatus.READY) {
            result.put("status", "SKIPPED");
            result.put("reason", "SOURCE_NOT_READY");
            return result;
        }
        List<DocChunk> chunks = chunkMapper.selectList(new QueryWrapper<DocChunk>()
                .eq("source_id", sourceId)
                .select("id", "content")
                .orderByAsc("seq"));
        List<DocChunk> all = chunks == null ? List.of() : chunks.stream()
                .filter(c -> c != null && c.getId() != null && StringUtils.hasText(c.getContent()))
                .toList();
        Map<Long, String> stored = embeddingRepository.loadShaByChunkIds(modelId,
                all.stream().map(DocChunk::getId).toList());
        List<DocChunk> todo = new ArrayList<>();
        List<String> shas = new ArrayList<>();
        for (DocChunk c : all) {
            String sha = ChunkEmbeddingRepository.contentSha(c.getContent());
            if (!sha.equals(stored.get(c.getId()))) {
                todo.add(c);
                shas.add(sha);
            }
        }
        int embedded = 0;
        try {
            for (int from = 0; from < todo.size(); from += EMBED_BATCH) {
                int to = Math.min(todo.size(), from + EMBED_BATCH);
                List<DocChunk> part = todo.subList(from, to);
                List<float[]> vectors = client.embed(part.stream().map(DocChunk::getContent).toList());
                if (vectors == null || vectors.size() != part.size()) {
                    throw new BusinessException(ResultCode.AI_ERROR, "嵌入返回条数与请求不符");
                }
                for (int i = 0; i < part.size(); i++) {
                    embeddingRepository.upsert(part.get(i).getId(), modelId, vectors.get(i), shas.get(from + i));
                    embedded++;
                }
                if (progress != null) {
                    progress.accept(10 + (int) (80L * to / todo.size()));
                }
            }
        } finally {
            if (embedded > 0 && events != null) {
                events.publishEvent(new RagCorpusChangedEvent(source.getCourseId(), "EMBED_DONE"));
            }
        }
        result.put("status", "DONE");
        result.put("total", all.size());
        result.put("embedded", embedded);
        result.put("skipped", all.size() - todo.size());
        log.info("RAG_EMBED done sourceId={} model={} total={} embedded={}", sourceId, modelId, all.size(), embedded);
        return result;
    }

    // ── 键 ───────────────────────────────────────────────────────────────

    static String autoKey(long sourceId, String modelId, long minChunkId) {
        return fit("emb:" + sourceId + ":", modelId, ":" + minChunkId);
    }

    static String reembedKey(long sourceId, String modelId, long epochMinute) {
        return fit("emb:" + sourceId + ":", modelId, ":r" + epochMinute);
    }

    private static String fit(String prefix, String modelId, String suffix) {
        String m = modelId == null ? "" : modelId;
        String key = prefix + m + suffix;
        if (key.length() <= MAX_KEY) {
            return key;
        }
        key = prefix + sha16(m) + suffix;
        return key.length() <= MAX_KEY ? key : key.substring(0, MAX_KEY);
    }

    private static String sha16(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> body(long sourceId, String modelId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceId", sourceId);
        body.put("model", modelId);
        return body;
    }
}
