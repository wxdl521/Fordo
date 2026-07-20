package com.wenjin.service.memory;

import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.StudentMemory;
import com.wenjin.mapper.StudentMemoryMapper;
import com.wenjin.support.MemoryKind;
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
 * 学生记忆写入：Jaccard 同义强化 vs 新插入；读时 effective 排序；注入格式化。
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
     * salience 默认 1.0；显式传入时夹到合理范围后落库。
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
        return upsertOrReinforce(studentId, courseId, kind, content, nodeCode, sourceRunId, null);
    }

    @Transactional
    public Long upsertOrReinforce(Long studentId,
                                  Long courseId,
                                  int kind,
                                  String content,
                                  String nodeCode,
                                  Long sourceRunId,
                                  BigDecimal salience) {
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
        created.setSalience(normalizeSalience(salience));
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
        return sortByEffective(rows);
    }

    /**
     * 取 top-k、按 kind 分组渲染 MEMORY 槽行，并强化被选中的条目。
     * 空结果返回 empty list（不注入伪对话）。
     */
    @Transactional
    public List<String> loadInjectLines(Long studentId, Long courseId) {
        if (studentId == null || courseId == null) {
            return List.of();
        }
        int k = Math.max(0, limits.getMemory().getInjectTopK());
        if (k == 0) {
            return List.of();
        }
        List<StudentMemory> ranked = listActiveByEffective(studentId, courseId);
        if (ranked.isEmpty()) {
            return List.of();
        }
        List<StudentMemory> selected = ranked.subList(0, Math.min(k, ranked.size()));
        for (StudentMemory row : selected) {
            reinforce(row.getId());
        }
        return formatInjectLines(selected);
    }

    /**
     * LIKE 检索 + kind 过滤 + effective 排序 + topK，命中后强化。
     */
    @Transactional
    public List<StudentMemory> searchAndReinforce(Long studentId,
                                                  Long courseId,
                                                  String query,
                                                  Integer kind,
                                                  int topK) {
        if (studentId == null || courseId == null || !StringUtils.hasText(query)) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(10, topK));
        String escaped = escapeLike(query.trim());
        List<StudentMemory> rows = memoryMapper.selectByContentLike(studentId, courseId, escaped, kind);
        List<StudentMemory> ranked = sortByEffective(rows);
        if (ranked.isEmpty()) {
            return List.of();
        }
        List<StudentMemory> hits = ranked.subList(0, Math.min(limit, ranked.size()));
        for (StudentMemory hit : hits) {
            reinforce(hit.getId());
        }
        return List.copyOf(hits);
    }

    /**
     * 分组渲染（不暴露分值）：
     * <pre>
     * 【学生画像】
     * · semantic
     * 【近期学习经历】
     * · episodic
     * 【有效策略】
     * · procedural
     * </pre>
     * 空分组省略整段标题。
     */
    public List<String> formatInjectLines(List<StudentMemory> selected) {
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        List<String> semantic = new ArrayList<>();
        List<String> episodic = new ArrayList<>();
        List<String> procedural = new ArrayList<>();
        for (StudentMemory m : selected) {
            if (m == null || !StringUtils.hasText(m.getContent())) {
                continue;
            }
            String bullet = "· " + formatContentLine(m);
            int kind = m.getKind() == null ? MemoryKind.EPISODIC : m.getKind();
            switch (kind) {
                case MemoryKind.SEMANTIC -> semantic.add(bullet);
                case MemoryKind.PROCEDURAL -> procedural.add(bullet);
                default -> episodic.add(bullet);
            }
        }
        List<String> lines = new ArrayList<>();
        appendSection(lines, "【学生画像】", semantic);
        appendSection(lines, "【近期学习经历】", episodic);
        appendSection(lines, "【有效策略】", procedural);
        return List.copyOf(lines);
    }

    private static void appendSection(List<String> out, String header, List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) {
            return;
        }
        out.add(header);
        out.addAll(bullets);
    }

    private static String formatContentLine(StudentMemory m) {
        String content = m.getContent().trim();
        if (StringUtils.hasText(m.getNodeCode())) {
            return content + "（" + m.getNodeCode().trim() + "）";
        }
        return content;
    }

    private List<StudentMemory> sortByEffective(List<StudentMemory> rows) {
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

    private static BigDecimal normalizeSalience(BigDecimal salience) {
        double v = salience == null ? 1.0 : salience.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            v = 1.0;
        }
        // DB 宽限；工具层会把 record_observation 夹到 0.5..1.5
        v = Math.max(0.1, Math.min(2.0, v));
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private static String clipNodeCode(String nodeCode) {
        if (!StringUtils.hasText(nodeCode)) {
            return null;
        }
        String t = nodeCode.trim();
        return t.length() > 32 ? t.substring(0, 32) : t;
    }

    /** Escape LIKE wildcards so user query is literal substring match. */
    static String escapeLike(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
