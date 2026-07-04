package com.wenjin.service;

import com.wenjin.dto.AdaptiveAnswerRequest;
import com.wenjin.dto.AdaptiveDiagnosticVO;

/**
 * 图引导自适应诊断服务（M2 图引导自适应诊断 T4）。
 *
 * <p>探针组卷（复用 M1 组卷组件 + AnswerGrader）→ 服务端判分 → 走既有
 * {@link MasteryService#applyAnswers} 实测落库 → 驱动 {@code AdaptiveEngine}
 * 状态传播与选点 → 会话状态序列化进 {@code adaptive_diag_session.state_json}
 * （服务器为唯一事实源，HTTP 调用间无状态）。
 *
 * <p>鉴权：controller（T6）对 start 挂 {@code assertAccessibleByStudent}、
 * 对 answer/abandon 挂 {@code assertSelf}；本 service 层额外反查 session 属主
 * （对齐 M1 submit 范式），双保险。
 */
public interface AdaptiveDiagnosticService {

    /**
     * 开始一次自适应诊断：建会话、组首探针（引擎选点 + 组卷）。
     *
     * @param studentId 学生 ID
     * @param courseId  课程 ID
     * @return 会话 + 首探针 + 进度；若空图/空题库一开局即终止，则 done=true、probe=null。
     */
    AdaptiveDiagnosticVO start(Long studentId, Long courseId);

    /**
     * 提交当前探针作答：判分 → 实测落库 → 状态传播 → 选下一探针 / 终止收尾。
     *
     * @param sessionId 会话 ID
     * @param req       作答请求（含 studentId 与逐题作答）
     * @return 下一探针 + 进度（未终止），或诊断摘要 + 结果页地址（已终止）
     */
    AdaptiveDiagnosticVO answer(Long sessionId, AdaptiveAnswerRequest req);

    /**
     * 中途放弃：status=0 → 2（+finished_at），已实测掌握度天然保留（applyAnswers 已即时落库）。
     * status=2 幂等成功；status=1（已完成）BAD_REQUEST。
     *
     * @param sessionId 会话 ID
     * @param studentId 学生 ID（反查属主）
     */
    void abandon(Long sessionId, Long studentId);
}
