package com.wenjin.dto;

import java.util.List;

/**
 * 一道近期错题（错因辅导 / {@code get_recent_mistakes}）。
 *
 * @param questionId         题目 ID
 * @param stem               题干（截断约 80 字）
 * @param type               题型：1 单选 / 2 多选 / 3 判断
 * @param chosen             学生所选选项（按作答顺序）
 * @param correct            正确选项（按 key 排序；无选项表时回退 question.answer）
 * @param misconceptionNodes 所选<b>错误</b>选项标注的误区节点（去重，已剔除归档节点）
 * @param primaryNode        题目主节点（question_node weight=1，可空）
 * @param answeredAt         作答时间（ISO-8601 本地时间）
 * @param scene              DIAGNOSTIC / PRACTICE
 * @param practiceSessionId  scene=PRACTICE 时的练习会话 ID（可空）
 */
public record MistakeVO(Long questionId,
                        String stem,
                        Integer type,
                        List<OptionRef> chosen,
                        List<OptionRef> correct,
                        List<NodeRef> misconceptionNodes,
                        NodeRef primaryNode,
                        String answeredAt,
                        String scene,
                        Long practiceSessionId) {

    public record OptionRef(String key, String text) {
    }

    public record NodeRef(String nodeCode, String name) {
    }
}
