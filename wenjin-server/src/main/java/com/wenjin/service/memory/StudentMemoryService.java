package com.wenjin.service.memory;

import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.StudentMemory;
import com.wenjin.mapper.StudentMemoryMapper;
import com.wenjin.support.MemoryStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 学生记忆写入：Jaccard 同义强化 vs 新插入；读时 effective 排序。
 */
@Service
public class StudentMemoryService {

    private final StudentMemoryMapper memoryMapper;
    private final MemoryJaccardDeduper jaccardDeduper;
    private final MemoryStrengthCalculator strengthCalculator;
    private final LimitsProperties limits;

    public StudentMemoryService(StudentMemoryMapper memoryMapper,
                                MemoryJaccardDeduper jaccardDeduper,
                                MemoryStrengthCalculator strengthCalculator,
                                LimitsProperties limits) {
        this.memoryMapper = Objects.requireNonNull(memoryMapper, "memoryMapper");
        this.jaccardDeduper = Objects.requireNonNull(jaccardDeduper, "jaccardDeduper");
        this.strengthCalculator = Objects.requireNonNull(strengthCalculator, "strengthCalculator");
        this.limits = limits == null ? new LimitsProperties() : limits;
    }

    /**
     * 写入候选记忆：同 kind 活跃行中若 Jaccard ≥ 阈值则强化已有行，否则插入。
     *
     * @return 被强化或新插入的记忆 id；content 为空时返回 null
     */
    @Transactional
    public Long upsertOrReinforce(Long studentId,
                                  Long courseId,
                                  int kind,
                                  String content,
                                  String nodeCode,
                                  Long sourceRunId) {
        String normalized = normalizeContent(content);
        if (!StringUtils.hasText(normalized) || studentId == null || courseId == null) {
            return null;
        }
        double threshold = limits.getMemory().getJaccardThreshold();
        List<StudentMemory> existing = memoryMapper.selectActive(studentId, courseId, kind);
        if (existing != null) {
            for (StudentMemory row : existing) {
                if (jaccardDeduper.isSynonym(normalized, row.getContent(), threshold)) {
                    reinforce(row.getId());
                    return row.getId();
                }
            }
        }
        StudentMemory created = new StudentMemory();
        created.setStudentId(studentId);
        created.setCourseId(courseId);
        created.setKind(kind);
        created.setContent(normalized);
        created.setNodeCode(clipNodeCode(nodeCode));
        created.setSourceRunId(sourceRunId);
        created.setSalience(BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP));
        LocalDateTime now = strengthCalculator.now();
        created.setLastReinforcedAt(now);
        created.setStatus(MemoryStatus.ACTIVE);
        created.setCreatedAt(now);
        memoryMapper.insert(created);
        return created.getId();
    }

    /** 命中/注入强化：刷新 last_reinforced_at。 */
    @Transactional
    public boolean reinforce(Long memoryId) {
        if (memoryId == null) {
            return false;
        }
        return memoryMapper.reinforce(memoryId, strengthCalculator.now()) == 1;
    }

    /**
     * 列出活跃记忆并按 effective 降序（次键 id 降序）。
     */
    public List<StudentMemory> listActiveByEffective(Long studentId, Long courseId) {
        List<StudentMemory> rows = memoryMapper.selectActive(studentId, courseId, null);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<StudentMemory> copy = new ArrayList<>(rows);
        copy.sort(Comparator
                .comparingDouble((StudentMemory m) -> strengthCalculator.effectiveOf(m))
                .reversed()
                .thenComparing(StudentMemory::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        return copy;
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        int max = Math.max(1, limits.getMemory().getMaxContentChars());
        if (trimmed.length() > max) {
            return trimmed.substring(0, max);
        }
        return trimmed;
    }

    private static String clipNodeCode(String nodeCode) {
        if (!StringUtils.hasText(nodeCode)) {
            return null;
        }
        String t = nodeCode.trim();
        return t.length() > 32 ? t.substring(0, 32) : t;
    }
}
