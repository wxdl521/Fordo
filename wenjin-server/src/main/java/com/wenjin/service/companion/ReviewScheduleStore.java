package com.wenjin.service.companion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.ReviewPin;
import com.wenjin.mapper.ReviewPinMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pin store for companion {@code schedule_review}.
 *
 * <p>Durable source of truth is {@code review_pin} (when mapper present). An in-process map
 * caches today's pins for low-latency companion tools. Dates use {@link #ZONE} (Asia/Shanghai)
 * so "today" matches Chinese classroom calendars even if the JVM default zone is UTC.
 */
@Component
public class ReviewScheduleStore {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public enum Mode {
        ADVANCE,
        TODAY
    }

    private final ReviewPinMapper pinMapper;
    private final Clock clock;
    private final ConcurrentHashMap<PinKey, Mode> memory = new ConcurrentHashMap<>();

    /** Spring ctor: DB-backed + system clock in Asia/Shanghai. */
    @Autowired
    public ReviewScheduleStore(ReviewPinMapper pinMapper) {
        this(pinMapper, Clock.system(ZONE));
    }

    /** Explicit clock (tests). */
    public ReviewScheduleStore(ReviewPinMapper pinMapper, Clock clock) {
        this.pinMapper = pinMapper;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Memory-only (unit tests without DB). */
    public ReviewScheduleStore() {
        this(null, Clock.system(ZONE));
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /**
     * Pin {@code nodeCode} onto today's review queue for the student/course.
     *
     * @return true if this is a new pin for the day; false if already pinned (mode may update)
     */
    public boolean pin(Long studentId, Long courseId, String nodeCode, Mode mode) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(mode, "mode");
        if (!StringUtils.hasText(nodeCode)) {
            throw new IllegalArgumentException("nodeCode is required");
        }
        String code = nodeCode.trim();
        LocalDate day = today();
        PinKey key = new PinKey(studentId, courseId, day, code);

        boolean existed = memory.containsKey(key);
        if (pinMapper != null) {
            Long dbCount = pinMapper.selectCount(new LambdaQueryWrapper<ReviewPin>()
                    .eq(ReviewPin::getStudentId, studentId)
                    .eq(ReviewPin::getCourseId, courseId)
                    .eq(ReviewPin::getPinDate, day)
                    .eq(ReviewPin::getNodeCode, code));
            existed = existed || (dbCount != null && dbCount > 0);
            pinMapper.upsert(studentId, courseId, day, code, mode.name());
        }
        memory.put(key, mode);
        return !existed;
    }

    /**
     * Today's forced pins for the student/course (nodeCode → mode).
     * Loads DB first when available, then overlays in-memory entries for the same day.
     */
    public Map<String, Mode> forcedToday(Long studentId, Long courseId) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(courseId, "courseId");
        LocalDate day = today();
        Map<String, Mode> result = new LinkedHashMap<>();

        if (pinMapper != null) {
            List<ReviewPin> rows = pinMapper.selectList(new LambdaQueryWrapper<ReviewPin>()
                    .eq(ReviewPin::getStudentId, studentId)
                    .eq(ReviewPin::getCourseId, courseId)
                    .eq(ReviewPin::getPinDate, day)
                    .orderByAsc(ReviewPin::getId));
            for (ReviewPin row : rows) {
                if (row == null || !StringUtils.hasText(row.getNodeCode())) {
                    continue;
                }
                Mode mode = parseMode(row.getMode());
                if (mode != null) {
                    result.put(row.getNodeCode().trim(), mode);
                    memory.put(new PinKey(studentId, courseId, day, row.getNodeCode().trim()), mode);
                }
            }
        }

        for (Map.Entry<PinKey, Mode> entry : memory.entrySet()) {
            PinKey key = entry.getKey();
            if (Objects.equals(key.studentId(), studentId)
                    && Objects.equals(key.courseId(), courseId)
                    && Objects.equals(key.date(), day)) {
                result.put(key.nodeCode(), entry.getValue());
            }
        }
        return result;
    }

    /** Clear all pins (eval reset / maintenance). */
    public void clear() {
        memory.clear();
        if (pinMapper != null) {
            pinMapper.delete(new LambdaQueryWrapper<>());
        }
    }

    /** Clear pins for one student×course (all dates). */
    public void clearStudent(Long studentId, Long courseId) {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(courseId, "courseId");
        memory.keySet().removeIf(key ->
                Objects.equals(key.studentId(), studentId) && Objects.equals(key.courseId(), courseId));
        if (pinMapper != null) {
            pinMapper.delete(new LambdaQueryWrapper<ReviewPin>()
                    .eq(ReviewPin::getStudentId, studentId)
                    .eq(ReviewPin::getCourseId, courseId));
        }
    }

    private static Mode parseMode(String text) {
        if (!StringUtils.hasText(text)) {
            return Mode.TODAY;
        }
        try {
            return Mode.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return Mode.TODAY;
        }
    }

    private record PinKey(Long studentId, Long courseId, LocalDate date, String nodeCode) {
    }
}
