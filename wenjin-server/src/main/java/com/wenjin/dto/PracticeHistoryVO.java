package com.wenjin.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点练习历史条目 VO（M1 练习闭环 T7）。
 *
 * <p>供前端显示"上次练习 3/5"所需最小字段集，不过度设计：
 * correctCount / totalQuestions 即可满足进度展示需求。</p>
 */
@Data
public class PracticeHistoryVO {

    /** 练习会话 ID */
    private Long sessionId;

    /** 练习知识点 ID */
    private Long nodeId;

    /** 知识点业务编码（如 KT12） */
    private String nodeCode;

    /** 知识点名称 */
    private String nodeName;

    /** 本次练习题目总数（来自 practice_session.question_ids 的题数） */
    private int totalQuestions;

    /**
     * 答对题数（仅对已提交会话有意义；进行中会话返回 0）。
     * 供前端显示"上次练习 3/5"中的"3"。
     */
    private int correctCount;

    /** 会话状态：0=进行中, 1=已提交 */
    private int status;

    /** 会话创建时间 */
    private LocalDateTime createdAt;
}
