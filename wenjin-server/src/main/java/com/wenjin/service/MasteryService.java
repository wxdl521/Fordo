package com.wenjin.service;

import com.wenjin.dto.GradedAnswer;

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
}
