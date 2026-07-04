package com.wenjin.service;

import com.wenjin.dto.GradedAnswer;

import java.math.BigDecimal;
import java.util.List;

/**
 * 掌握度服务：依据一次作答的判分结果更新学生掌握度。
 */
public interface MasteryService {

    /**
     * 按作答顺序逐题更新掌握度：某考点本次 submit 首次接触走冷启动直给，
     * 否则走 EWMA；每次更新 upsert student_mastery 并向 mastery_snapshot 追加一条快照。
     * 同一 submit 内某点被多题命中时，用内存运行值累进（避免事务内读己写）。
     *
     * @param studentId 学生 ID
     * @param courseId  课程 ID
     * @param answers   逐题判分结果（顺序即作答顺序）
     */
    void applyAnswers(Long studentId, Long courseId, List<GradedAnswer> answers);

    /**
     * 分值 → 掌握等级（最小暴露，供 M2 T5 推断保守初值落库时复用，不重写阈值逻辑）。
     * <p>2=已掌握（≥ masteredThreshold）；1=薄弱（≥ weakThreshold）；0=未学。
     *
     * @param score 掌握度分值（0–100）
     * @return 0 / 1 / 2
     */
    int levelOf(double score);

    /**
     * 向 mastery_snapshot 追加一条快照记录（最小暴露，供 M2 T5 推断节点落库后复用）。
     * <p>行为与 {@link MasteryServiceImpl} 内既有私有方法完全相同，零修改语义。
     *
     * @param studentId 学生 ID
     * @param courseId  课程 ID
     * @param nodeId    知识点 ID
     * @param score     掌握度分值
     * @param level     掌握等级
     */
    void appendSnapshot(Long studentId, Long courseId, Long nodeId, BigDecimal score, int level);
}
