package com.wenjin.support;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.GraphImportRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽取草稿的内存暂存(单实例演示用)。draftId → 原始草稿 + 课程编码 + 创建时间。
 * 惰性过期:默认 30 分钟,取用时判断过期则移除并视为不存在。
 */
@Component
public class GraphDraftStore {

    static final Duration TTL = Duration.ofMinutes(30);

    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public GraphDraftStore() {
        this(Clock.systemUTC());
    }

    public GraphDraftStore(Clock clock) {
        this.clock = clock;
    }

    public String save(String courseCode, GraphImportRequest draft) {
        String id = UUID.randomUUID().toString();
        store.put(id, new Entry(courseCode, draft, Instant.now(clock)));
        return id;
    }

    public Entry get(String draftId) {
        Entry e = store.get(draftId);
        if (e == null || Duration.between(e.createdAt, Instant.now(clock)).compareTo(TTL) > 0) {
            store.remove(draftId);
            throw new BusinessException(ResultCode.NOT_FOUND, "草稿已过期或不存在,请重新上传");
        }
        return e;
    }

    public void remove(String draftId) {
        store.remove(draftId);
    }

    /** 暂存条目。 */
    public static class Entry {
        public final String courseCode;
        public final GraphImportRequest draft;
        public final Instant createdAt;

        Entry(String courseCode, GraphImportRequest draft, Instant createdAt) {
            this.courseCode = courseCode;
            this.draft = draft;
            this.createdAt = createdAt;
        }
    }
}
