package com.wenjin.service.companion;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.entity.ProbeSessionRow;
import com.wenjin.mapper.ProbeSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** MySQL 实现（V26 {@code probe_session}）。时间按 Asia/Shanghai 墙钟存取，与 JDBC serverTimezone 一致。 */
@Repository
public class MybatisProbeSessionRepository implements ProbeSessionRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisProbeSessionRepository.class);
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<ProbeSessionStore.ProbeOption>> OPTIONS = new TypeReference<>() {
    };

    private final ProbeSessionMapper mapper;

    public MybatisProbeSessionRepository(ProbeSessionMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void insert(ProbeSessionStore.ProbeSession s) {
        ProbeSessionRow row = new ProbeSessionRow();
        row.setSessionId(s.sessionId());
        row.setStudentId(s.studentId());
        row.setCourseId(s.courseId());
        row.setNodeCode(s.nodeCode());
        row.setNodeId(s.nodeId());
        row.setStem(s.stem());
        row.setType(s.type());
        row.setOptionsJson(writeOptions(s.options()));
        row.setCorrectKey(s.correctKey());
        row.setSource(s.source().name());
        row.setBankQuestionId(s.bankQuestionId());
        row.setCreatedAt(toLocal(s.createdAt()));
        mapper.insert(row);
    }

    @Override
    public Optional<ProbeSessionStore.ProbeSession> findById(String sessionId) {
        ProbeSessionRow row = mapper.selectById(sessionId);
        return Optional.ofNullable(row).map(MybatisProbeSessionRepository::toSession);
    }

    @Override
    public boolean markSubmitted(String sessionId, Instant at, boolean correct) {
        return mapper.markSubmitted(sessionId, toLocal(at), correct) > 0;
    }

    @Override
    public Set<Long> usedBankQuestionIds(Long studentId, Long courseId, String nodeCode) {
        List<ProbeSessionRow> rows = mapper.selectList(new QueryWrapper<ProbeSessionRow>()
                .select("bank_question_id")
                .eq("student_id", studentId)
                .eq("course_id", courseId)
                .eq("node_code", nodeCode)
                .isNotNull("bank_question_id"));
        Set<Long> used = new HashSet<>();
        for (ProbeSessionRow r : rows) {
            if (r.getBankQuestionId() != null) {
                used.add(r.getBankQuestionId());
            }
        }
        return used;
    }

    @Override
    public int deleteCreatedBefore(Instant cutoff) {
        return mapper.deleteCreatedBefore(toLocal(cutoff));
    }

    @Override
    public void deleteAll() {
        mapper.delete(new QueryWrapper<>());
    }

    @Override
    public long count() {
        Long n = mapper.selectCount(new QueryWrapper<>());
        return n == null ? 0 : n;
    }

    private static ProbeSessionStore.ProbeSession toSession(ProbeSessionRow r) {
        return new ProbeSessionStore.ProbeSession(
                r.getSessionId(), r.getStudentId(), r.getCourseId(), r.getNodeCode(), r.getNodeId(),
                r.getStem(), r.getType(), readOptions(r.getOptionsJson()), r.getCorrectKey(),
                ProbeSessionStore.Source.valueOf(r.getSource()), r.getBankQuestionId(),
                toInstant(r.getCreatedAt()), r.getSubmittedAt() != null, r.getCorrect());
    }

    private static String writeOptions(List<ProbeSessionStore.ProbeOption> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("probe options serialize failed", e);
        }
    }

    private static List<ProbeSessionStore.ProbeOption> readOptions(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return JSON.readValue(json, OPTIONS);
        } catch (JsonProcessingException e) {
            log.warn("probe options JSON unreadable, treating as empty: {}", e.getMessage());
            return List.of();
        }
    }

    static LocalDateTime toLocal(Instant t) {
        return t == null ? null : LocalDateTime.ofInstant(t, ZONE);
    }

    static Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.atZone(ZONE).toInstant();
    }
}
