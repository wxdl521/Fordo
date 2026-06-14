package com.wenjin.service;

import com.wenjin.dto.AnnotateItemResult;
import com.wenjin.dto.AnnotateRequest;
import com.wenjin.dto.GenerateResult;
import com.wenjin.dto.ImportBankResult;

import java.util.List;

/**
 * 题目服务。阶段二放出题流水线（AI 出题即标注）与存量题标注流水线（Prompt 3，超纲不强标）。
 */
public interface QuestionService {

    /**
     * 为目标知识点生成 count 道单选题（出题即标注）：
     * 取白名单 → 调 AI 出题 → 代码侧校验（恰一个正确项、答案在选项内、考点⊆白名单）
     * → 不足则整体重试一次 → 去重 → 落库（question/option/question_node）。
     *
     * @param nodeCode 目标知识点编码
     * @param count    期望生成数量
     * @return 出题结果摘要
     */
    GenerateResult generate(String nodeCode, int count);

    /**
     * 存量题标注（Prompt 3，超纲不强标）：
     * 取课程全图白名单 → 逐题调 AI 标注 → 超纲（mainPoint=null）则不落库、带 reason 回传；
     * 否则代码侧校验（mainPoint/subPoints/干扰项考点⊆白名单）后落库
     * （question status=PENDING source=1 + options + question_node）。
     *
     * @param req 待标注题目批
     * @return 每题标注结果（含是否落库）
     */
    List<AnnotateItemResult> annotate(AnnotateRequest req);

    /**
     * 题库种子导入：读题库 JSON → 按 courseCode 定位课程 →
     * 逐题落库 question(status=已通过, source=1, type=1) + options(含 correct 标志)
     * + question_node(nodeCode→node_id, weight=1)；同课程题干已存在则跳过。
     *
     * @param courseCode 课程业务编码
     * @return 导入/跳过计数
     */
    ImportBankResult importBank(String courseCode);
}
