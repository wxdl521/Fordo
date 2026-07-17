package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.dto.GradedAnswer;
import com.wenjin.entity.MasterySnapshot;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.MasterySnapshotMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 掌握度服务实现（阶段三核心）。
 * <p>
 * 逐题（按作答顺序）→ 取题目难度 + question_node(node_id+weight) → 对每个考点：
 *   首次接触走冷启动直给，否则走 EWMA；定级纯阈值；upsert student_mastery 并追加 mastery_snapshot。
 * 一次 submit 内某点被多题命中：用内存 working map 缓存运行值，首次冷启动、其余 EWMA 累进，每步各一条快照。
 * <p>
 * 并发：短事务内按 nodeId 升序 {@code INSERT … ON DUPLICATE KEY} 占位 + {@code SELECT … FOR UPDATE}
 * 串行化同点更新；服务边界死锁最多重试 3 次（指数退避）。
 */
@Service
public class MasteryServiceImpl implements MasteryService {

    /** 主点权重值 */
    private static final int WEIGHT_MAIN = 1;
    /** 难度因子基准：难度因子 = 难度 / 3.0 */
    private static final double DIFFICULTY_PIVOT = 3.0;
    /** 主点幅度系数 */
    private static final double MAIN_FACTOR = 1.0;
    /** 次点幅度系数 */
    private static final double SUB_FACTOR = 0.5;
    /** 题目难度缺省值 */
    private static final int DEFAULT_DIFFICULTY = 3;
    /** 冷启动答对基准分 */
    private static final double COLD_START_CORRECT_BASE = 65.0;
    /** 冷启动答错基准分 */
    private static final double COLD_START_WRONG_BASE = 30.0;
    /** 冷启动每级难度加分步长 */
    private static final double COLD_START_STEP = 5.0;
    /** EWMA 单题有效步长封顶：避免高难度主点单题把掌握度拉动过猛（演示时"答一题地图猛跳"） */
    private static final double EFF_ALPHA_CAP = 0.4;
    /** 死锁 / 锁等待重试上限（含首次） */
    private static final int MAX_DEADLOCK_ATTEMPTS = 3;
    /** 首次退避毫秒 */
    private static final long DEADLOCK_BACKOFF_MS = 40L;

    private final QuestionMapper questionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final StudentMasteryMapper studentMasteryMapper;
    private final MasterySnapshotMapper masterySnapshotMapper;
    private final TransactionTemplate requiredTx;

    /** EWMA 平滑系数基准 */
    @Value("${wenjin.mastery.alpha:0.3}")
    private double alpha;
    /** 已掌握阈值 */
    @Value("${wenjin.mastery.mastered-threshold:75}")
    private double masteredThreshold;
    /** 薄弱阈值 */
    @Value("${wenjin.mastery.weak-threshold:40}")
    private double weakThreshold;

    public MasteryServiceImpl(QuestionMapper questionMapper,
                              QuestionNodeMapper questionNodeMapper,
                              StudentMasteryMapper studentMasteryMapper,
                              MasterySnapshotMapper masterySnapshotMapper,
                              PlatformTransactionManager transactionManager) {
        this.questionMapper = questionMapper;
        this.questionNodeMapper = questionNodeMapper;
        this.studentMasteryMapper = studentMasteryMapper;
        this.masterySnapshotMapper = masterySnapshotMapper;
        this.requiredTx = new TransactionTemplate(transactionManager);
        this.requiredTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.requiredTx.setName("mastery-applyAnswers");
    }

    @Override
    public void applyAnswers(Long studentId, Long courseId, List<GradedAnswer> answers) {
        if (answers == null || answers.isEmpty()) {
            return;
        }

        // 1. 批量取题目难度（只读，事务外）
        List<Long> questionIds = answers.stream()
                .map(GradedAnswer::getQuestionId)
                .distinct()
                .collect(Collectors.toList());
        List<Question> questions = questionMapper.selectList(
                new LambdaQueryWrapper<Question>().in(Question::getId, questionIds));
        Map<Long, Integer> difficultyByQuestion = new HashMap<>();
        for (Question q : questions) {
            difficultyByQuestion.put(q.getId(),
                    q.getDifficulty() == null ? DEFAULT_DIFFICULTY : q.getDifficulty());
        }

        // 2. 批量取题-知识点关联，按题分组
        List<QuestionNode> qnodes = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>().in(QuestionNode::getQuestionId, questionIds));
        Map<Long, List<QuestionNode>> nodesByQuestion = qnodes.stream()
                .collect(Collectors.groupingBy(QuestionNode::getQuestionId));

        // 3. 涉及节点（排序在短事务内加锁前再做一次，防死锁）
        Set<Long> nodeIds = qnodes.stream().map(QuestionNode::getNodeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (nodeIds.isEmpty()) {
            return;
        }

        // 4. 外层事务内单次写；standalone 才开启短事务并做 bounded deadlock retry
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            applyAnswersLocked(studentId, courseId, answers, difficultyByQuestion, nodesByQuestion, nodeIds);
            return;
        }
        runWithDeadlockRetry(() -> requiredTx.executeWithoutResult(status ->
                applyAnswersLocked(studentId, courseId, answers, difficultyByQuestion, nodesByQuestion, nodeIds)));
    }

    /**
     * 持锁更新：按 nodeId 升序占位并 FOR UPDATE，再按作答顺序累进写分 + 快照。
     */
    private void applyAnswersLocked(Long studentId, Long courseId, List<GradedAnswer> answers,
                                    Map<Long, Integer> difficultyByQuestion,
                                    Map<Long, List<QuestionNode>> nodesByQuestion,
                                    Set<Long> nodeIds) {
        List<Long> sortedNodeIds = new ArrayList<>(nodeIds);
        sortedNodeIds.sort(Long::compareTo);

        Map<Long, StudentMastery> currentRows = new HashMap<>();
        Set<Long> newlyCreated = new HashSet<>();
        for (Long nodeId : sortedNodeIds) {
            // INSERT ON DUPLICATE KEY UPDATE id=id 占位；1=新行（冷启动），0/2=已存在（EWMA）。
            // 依赖 JDBC useAffectedRows=true；若驱动返回含糊，再以 DuplicateKey 兜底语义不适用此处。
            boolean created = ensureMasteryRow(studentId, courseId, nodeId);
            StudentMastery locked = studentMasteryMapper.selectForUpdate(studentId, courseId, nodeId);
            if (locked == null) {
                ensureMasteryRow(studentId, courseId, nodeId);
                locked = studentMasteryMapper.selectForUpdate(studentId, courseId, nodeId);
            }
            if (locked == null) {
                throw new IllegalStateException(
                        "student_mastery row missing after insertEnsure: student="
                                + studentId + " course=" + courseId + " node=" + nodeId);
            }
            currentRows.put(nodeId, locked);
            if (created) {
                newlyCreated.add(nodeId);
            }
        }

        Map<Long, Double> working = new HashMap<>();

        for (GradedAnswer ans : answers) {
            int difficulty = difficultyByQuestion.getOrDefault(ans.getQuestionId(), DEFAULT_DIFFICULTY);
            List<QuestionNode> nodes = nodesByQuestion.getOrDefault(ans.getQuestionId(), List.of());

            List<QuestionNode> ordered = new ArrayList<>(nodes);
            ordered.sort(Comparator
                    .comparingInt((QuestionNode qn) -> qn.getWeight() == null ? WEIGHT_MAIN : qn.getWeight())
                    .thenComparingLong(QuestionNode::getNodeId));

            for (QuestionNode qn : ordered) {
                Long nodeId = qn.getNodeId();
                int weight = qn.getWeight() == null ? WEIGHT_MAIN : qn.getWeight();
                boolean firstContact = !working.containsKey(nodeId) && newlyCreated.contains(nodeId);

                double newScore;
                if (firstContact) {
                    newScore = ans.isCorrect()
                            ? Math.min(COLD_START_CORRECT_BASE + COLD_START_STEP * difficulty, 100)
                            : Math.max(COLD_START_WRONG_BASE + COLD_START_STEP * difficulty, 0);
                } else {
                    double oldScore = working.containsKey(nodeId)
                            ? working.get(nodeId)
                            : currentRows.get(nodeId).getMasteryScore().doubleValue();
                    double targetVal = ans.isCorrect() ? 100.0 : 0.0;
                    double weightFactor = weight == WEIGHT_MAIN ? MAIN_FACTOR : SUB_FACTOR;
                    double effAlpha = Math.min(
                            clamp(alpha * (difficulty / DIFFICULTY_PIVOT) * weightFactor, 0, 1),
                            EFF_ALPHA_CAP);
                    newScore = clamp(oldScore + effAlpha * (targetVal - oldScore), 0, 100);
                }

                BigDecimal score = BigDecimal.valueOf(newScore).setScale(2, RoundingMode.HALF_UP);
                double rounded = score.doubleValue();
                int level = levelOf(rounded);
                working.put(nodeId, rounded);

                updateLocked(currentRows, nodeId, score, level);
                appendSnapshot(studentId, courseId, nodeId, score, level);
            }
        }
    }

    /**
     * 占位插入：ON DUPLICATE KEY UPDATE id=id。
     *
     * @return true 若本事务新建了行（冷启动）；false 若行已存在
     */
    private boolean ensureMasteryRow(Long studentId, Long courseId, Long nodeId) {
        try {
            int affected = studentMasteryMapper.insertEnsure(studentId, courseId, nodeId);
            // MySQL：1=插入；0=已存在且值未变；2=已存在且更新（本 SQL 不会到 2）
            return affected == 1;
        } catch (DuplicateKeyException ex) {
            return false;
        } catch (DataIntegrityViolationException ex) {
            // 部分驱动将重复键包成更宽的 DIV
            if (isDuplicateKey(ex)) {
                return false;
            }
            throw ex;
        }
    }

    private static boolean isDuplicateKey(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                // MySQL ER_DUP_ENTRY
                if (sql.getErrorCode() == 1062) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /** 行已由 insertEnsure 保证存在：始终 updateById。 */
    private void updateLocked(Map<Long, StudentMastery> currentRows, Long nodeId,
                              BigDecimal score, int level) {
        StudentMastery row = currentRows.get(nodeId);
        StudentMastery update = new StudentMastery();
        update.setId(row.getId());
        update.setStudentId(row.getStudentId());
        update.setCourseId(row.getCourseId());
        update.setNodeId(row.getNodeId());
        update.setMasteryScore(score);
        update.setMasteryLevel(level);
        studentMasteryMapper.updateById(update);
        // 同步内存行，供同事务后续逻辑读取
        row.setMasteryScore(score);
        row.setMasteryLevel(level);
    }

    /** 每次更新追加一条快照（只增不改，成长曲线数据来源）。 */
    @Override
    public void appendSnapshot(Long studentId, Long courseId, Long nodeId,
                               BigDecimal score, int level) {
        MasterySnapshot snap = new MasterySnapshot();
        snap.setStudentId(studentId);
        snap.setCourseId(courseId);
        snap.setNodeId(nodeId);
        snap.setMasteryScore(score);
        snap.setMasteryLevel(level);
        snap.setSnapshotAt(LocalDateTime.now());
        masterySnapshotMapper.insert(snap);
    }

    @Override
    public int levelOf(double score) {
        if (score >= masteredThreshold) {
            return 2;
        }
        if (score >= weakThreshold) {
            return 1;
        }
        return 0;
    }

    private void runWithDeadlockRetry(Runnable action) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                action.run();
                return;
            } catch (RuntimeException ex) {
                if (attempt >= MAX_DEADLOCK_ATTEMPTS || !isRetryableConcurrency(ex)) {
                    throw ex;
                }
                try {
                    Thread.sleep(DEADLOCK_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    private static boolean isRetryableConcurrency(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof DeadlockLoserDataAccessException
                    || t instanceof CannotAcquireLockException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                // 1213 deadlock; 1205 lock wait timeout
                if (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
