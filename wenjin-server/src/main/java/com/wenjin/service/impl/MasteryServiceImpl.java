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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    private final QuestionMapper questionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final StudentMasteryMapper studentMasteryMapper;
    private final MasterySnapshotMapper masterySnapshotMapper;

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
                              MasterySnapshotMapper masterySnapshotMapper) {
        this.questionMapper = questionMapper;
        this.questionNodeMapper = questionNodeMapper;
        this.studentMasteryMapper = studentMasteryMapper;
        this.masterySnapshotMapper = masterySnapshotMapper;
    }

    @Override
    public void applyAnswers(Long studentId, Long courseId, List<GradedAnswer> answers) {
        if (answers == null || answers.isEmpty()) {
            return;
        }

        // 1. 批量取题目难度
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

        // 3. 批量取已有掌握度行（学生×课程×涉及节点）
        Set<Long> nodeIds = qnodes.stream().map(QuestionNode::getNodeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, StudentMastery> currentRows = new HashMap<>();
        if (!nodeIds.isEmpty()) {
            List<StudentMastery> existing = studentMasteryMapper.selectList(
                    new LambdaQueryWrapper<StudentMastery>()
                            .eq(StudentMastery::getStudentId, studentId)
                            .eq(StudentMastery::getCourseId, courseId)
                            .in(StudentMastery::getNodeId, nodeIds));
            for (StudentMastery sm : existing) {
                currentRows.put(sm.getNodeId(), sm);
            }
        }

        // working map：本次 submit 内某点的运行值（四舍五入后），避免事务内读己写
        Map<Long, Double> working = new HashMap<>();

        // 4. 按作答顺序逐题更新
        for (GradedAnswer ans : answers) {
            int difficulty = difficultyByQuestion.getOrDefault(ans.getQuestionId(), DEFAULT_DIFFICULTY);
            List<QuestionNode> nodes = nodesByQuestion.getOrDefault(ans.getQuestionId(), List.of());

            // 主点先于次点，nodeId 升序，保证确定性
            List<QuestionNode> ordered = new ArrayList<>(nodes);
            ordered.sort(Comparator
                    .comparingInt((QuestionNode qn) -> qn.getWeight() == null ? WEIGHT_MAIN : qn.getWeight())
                    .thenComparingLong(QuestionNode::getNodeId));

            for (QuestionNode qn : ordered) {
                Long nodeId = qn.getNodeId();
                int weight = qn.getWeight() == null ? WEIGHT_MAIN : qn.getWeight();
                boolean firstContact = !working.containsKey(nodeId) && !currentRows.containsKey(nodeId);

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

                upsert(currentRows, studentId, courseId, nodeId, score, level);
                appendSnapshot(studentId, courseId, nodeId, score, level);
            }
        }
    }

    /** upsert：内存中无该点行则 insert（并登记），否则 updateById。 */
    private void upsert(Map<Long, StudentMastery> currentRows, Long studentId, Long courseId,
                        Long nodeId, BigDecimal score, int level) {
        StudentMastery row = currentRows.get(nodeId);
        if (row == null) {
            row = new StudentMastery();
            row.setStudentId(studentId);
            row.setCourseId(courseId);
            row.setNodeId(nodeId);
            row.setMasteryScore(score);
            row.setMasteryLevel(level);
            studentMasteryMapper.insert(row);
            currentRows.put(nodeId, row);
        } else {
            // 构造新对象传给 updateById，避免与 insert 的捕获对象共享引用（TDD 测试可断言各自值）
            StudentMastery update = new StudentMastery();
            update.setId(row.getId());
            update.setStudentId(row.getStudentId());
            update.setCourseId(row.getCourseId());
            update.setNodeId(row.getNodeId());
            update.setMasteryScore(score);
            update.setMasteryLevel(level);
            // updated_at 不设：DB 列含 ON UPDATE CURRENT_TIMESTAMP，MP 默认 NOT_NULL 策略跳过 null 字段
            studentMasteryMapper.updateById(update);
            // working map 已缓存运行值，currentRows 仅用于判断"是否已有行"，无需再改 score
        }
    }

    /** 每次更新追加一条快照（只增不改，成长曲线数据来源）。 */
    private void appendSnapshot(Long studentId, Long courseId, Long nodeId,
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

    private int levelOf(double score) {
        if (score >= masteredThreshold) {
            return 2;
        }
        if (score >= weakThreshold) {
            return 1;
        }
        return 0;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
