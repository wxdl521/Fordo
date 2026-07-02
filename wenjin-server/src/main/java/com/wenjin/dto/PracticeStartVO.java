package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 节点练习开始响应 VO（学生侧，题目使用同款脱敏结构）。
 *
 * <p>含三部分：会话 ID（供后续 submit 引用）、练习知识点基本信息、脱敏题目列表。
 * 绝不携带 isCorrect / pointNodeCode / answer 等答案敏感字段。</p>
 */
@Data
public class PracticeStartVO {

    /** 练习会话 ID（落库后自增，客户端 submit 时回传） */
    private Long sessionId;

    /** 练习目标知识点摘要 */
    private NodeRef node;

    /** 脱敏题目列表（与 practice_session.question_ids 冻结顺序一致） */
    private List<PaperQuestionVO> questions;

    /** 知识点引用（练习入口展示用，不含评分/掌握度等计算字段）。 */
    @Data
    public static class NodeRef {

        /** 知识点数据库主键（kg_node.id） */
        private Long nodeId;

        /** 知识点业务编码（如 KT12） */
        private String nodeCode;

        /** 知识点名称 */
        private String name;
    }
}
