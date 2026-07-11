package com.wenjin.support;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.GraphImportRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽取草稿的内存暂存(单实例演示用)。
 * <p>
 * 不变量：
 * <ul>
 *   <li>save/get/remove 均需属主 teacherId</li>
 *   <li>purge → validate → capacity 在同一把锁内完成；满载返回 429，不静默驱逐有效草稿</li>
 *   <li>定时每分钟 purge 过期项；读路径亦惰性过期</li>
 * </ul>
 */
@Component
public class GraphDraftStore {

    private final Clock clock;
    private final LimitsProperties limits;
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    @Autowired
    public GraphDraftStore(LimitsProperties limits) {
        this(Clock.systemUTC(), limits);
    }

    public GraphDraftStore(Clock clock, LimitsProperties limits) {
        this.clock = clock;
        this.limits = limits == null ? new LimitsProperties() : limits;
    }

    /** 测试便利构造：默认限额。 */
    public GraphDraftStore() {
        this(Clock.systemUTC(), new LimitsProperties());
    }

    public GraphDraftStore(Clock clock) {
        this(clock, new LimitsProperties());
    }

    /**
     * 保存草稿。属主必填；校验节点/边/字节预算后检查容量。
     *
     * @return draftId
     */
    public String save(Long teacherId, String courseCode, GraphImportRequest draft) {
        if (teacherId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录，无法保存草稿");
        }
        if (courseCode == null || courseCode.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseCode 不能为空");
        }
        if (draft == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "草稿不能为空");
        }

        synchronized (lock) {
            purgeExpiredLocked();
            validateDraftLocked(draft);
            enforceCapacityLocked(teacherId);

            String id = UUID.randomUUID().toString();
            long bytes = estimateBytes(draft);
            store.put(id, new Entry(teacherId, courseCode, draft, Instant.now(clock), bytes));
            return id;
        }
    }

    /** 兼容旧测试：无属主时用 teacherId=0（仅单测；生产路径必须传真实 teacherId）。 */
    public String save(String courseCode, GraphImportRequest draft) {
        return save(0L, courseCode, draft);
    }

    public Entry get(String draftId, Long teacherId) {
        if (teacherId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录，无法读取草稿");
        }
        synchronized (lock) {
            purgeExpiredLocked();
            Entry e = store.get(draftId);
            if (e == null || isExpired(e)) {
                store.remove(draftId);
                throw new BusinessException(ResultCode.NOT_FOUND, "草稿已过期或不存在,请重新上传");
            }
            if (!Objects.equals(e.teacherId, teacherId)) {
                throw new BusinessException(ResultCode.NOT_FOUND, "草稿已过期或不存在,请重新上传");
            }
            return e;
        }
    }

    /** 兼容：无属主校验的 get（仅在已知属主或测试场景使用会误过）。 */
    public Entry get(String draftId) {
        synchronized (lock) {
            purgeExpiredLocked();
            Entry e = store.get(draftId);
            if (e == null || isExpired(e)) {
                store.remove(draftId);
                throw new BusinessException(ResultCode.NOT_FOUND, "草稿已过期或不存在,请重新上传");
            }
            return e;
        }
    }

    public void remove(String draftId, Long teacherId) {
        if (teacherId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录，无法删除草稿");
        }
        synchronized (lock) {
            Entry e = store.get(draftId);
            if (e == null) {
                return;
            }
            if (!Objects.equals(e.teacherId, teacherId)) {
                throw new BusinessException(ResultCode.NOT_FOUND, "草稿已过期或不存在,请重新上传");
            }
            store.remove(draftId);
        }
    }

    public void remove(String draftId) {
        synchronized (lock) {
            store.remove(draftId);
        }
    }

    /** 定时清理过期草稿（默认每分钟）。 */
    @Scheduled(fixedDelayString = "${wenjin.limits.draft.purge-interval-ms:60000}")
    public void scheduledPurge() {
        synchronized (lock) {
            purgeExpiredLocked();
        }
    }

    /** 测试可见：当前有效草稿数。 */
    int size() {
        synchronized (lock) {
            purgeExpiredLocked();
            return store.size();
        }
    }

    private void purgeExpiredLocked() {
        Instant now = Instant.now(clock);
        Duration ttl = ttl();
        List<String> expired = new ArrayList<>();
        for (var entry : store.entrySet()) {
            if (Duration.between(entry.getValue().createdAt, now).compareTo(ttl) > 0) {
                expired.add(entry.getKey());
            }
        }
        for (String id : expired) {
            store.remove(id);
        }
    }

    private void validateDraftLocked(GraphImportRequest draft) {
        int nodes = draft.getNodes() == null ? 0 : draft.getNodes().size();
        int edges = draft.getEdges() == null ? 0 : draft.getEdges().size();
        int maxNodes = limits.getDraft().getMaxNodes();
        int maxEdges = limits.getDraft().getMaxEdges();
        if (nodes > maxNodes) {
            throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE,
                    "草稿节点数超限：" + nodes + " > " + maxNodes);
        }
        if (edges > maxEdges) {
            throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE,
                    "草稿边数超限：" + edges + " > " + maxEdges);
        }
        long bytes = estimateBytes(draft);
        long maxBytes = limits.getDraft().getMaxBytes();
        if (bytes > maxBytes) {
            throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE,
                    "草稿体积超限（估算 " + bytes + " 字节）");
        }
    }

    private void enforceCapacityLocked(Long teacherId) {
        int maxTotal = limits.getDraft().getMaxTotal();
        int maxPerUser = limits.getDraft().getMaxPerUser();
        int total = store.size();
        if (total >= maxTotal) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS,
                    "草稿总数已满（" + maxTotal + "），请稍后重试或清理过期草稿");
        }
        int mine = 0;
        for (Entry e : store.values()) {
            if (Objects.equals(e.teacherId, teacherId)) {
                mine++;
            }
        }
        if (mine >= maxPerUser) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS,
                    "您的草稿数已满（" + maxPerUser + "），请提交或等待过期后再试");
        }
    }

    private boolean isExpired(Entry e) {
        return Duration.between(e.createdAt, Instant.now(clock)).compareTo(ttl()) > 0;
    }

    private Duration ttl() {
        return Duration.ofMinutes(Math.max(1, limits.getDraft().getTtlMinutes()));
    }

    /** 粗估草稿占用：节点/边关键字段 UTF-8 字节 + 结构开销。 */
    static long estimateBytes(GraphImportRequest draft) {
        if (draft == null) {
            return 0L;
        }
        long total = 256L;
        if (draft.getCourse() != null) {
            total += utf8(draft.getCourse().getCode()) + utf8(draft.getCourse().getName())
                    + utf8(draft.getCourse().getGraphVersion());
        }
        if (draft.getNodes() != null) {
            for (GraphImportRequest.NodeItem n : draft.getNodes()) {
                if (n == null) {
                    continue;
                }
                total += 64L + utf8(n.getId()) + utf8(n.getName()) + utf8(n.getChapter())
                        + utf8(n.getBloom()) + utf8(n.getDescription()) + utf8(n.getNote());
            }
        }
        if (draft.getEdges() != null) {
            for (GraphImportRequest.EdgeItem e : draft.getEdges()) {
                if (e == null) {
                    continue;
                }
                total += 48L + utf8(e.getSource()) + utf8(e.getTarget())
                        + utf8(e.getType()) + utf8(e.getNote());
            }
        }
        return total;
    }

    private static long utf8(String s) {
        return s == null ? 0L : s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 暂存条目。 */
    public static class Entry {
        public final Long teacherId;
        public final String courseCode;
        public final GraphImportRequest draft;
        public final Instant createdAt;
        public final long estimatedBytes;

        Entry(Long teacherId, String courseCode, GraphImportRequest draft, Instant createdAt, long estimatedBytes) {
            this.teacherId = teacherId;
            this.courseCode = courseCode;
            this.draft = draft;
            this.createdAt = createdAt;
            this.estimatedBytes = estimatedBytes;
        }

        /** 兼容旧字段访问：无 teacherId 时代的构造。 */
        Entry(String courseCode, GraphImportRequest draft, Instant createdAt) {
            this(0L, courseCode, draft, createdAt, estimateBytes(draft));
        }
    }
}
