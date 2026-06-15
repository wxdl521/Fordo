package com.wenjin.ai;

import com.wenjin.ai.dto.AiAnnotation;
import com.wenjin.ai.dto.AiQuestion;

/**
 * 题目 AI 客户端：出题（Prompt 2）与存量题标注（Prompt 3）。
 * 真实现走 OpenAI 兼容接口；约束（仅白名单内、单选、恰一个正确）写在 prompt 中。
 */
public interface QuestionAiClient {

    /**
     * 出题即标注：在白名单内为目标节点生成 count 道单选题。
     *
     * @param targetNodeCode 目标节点 code（主考点）
     * @param targetName     目标节点名称
     * @param chapter        目标节点所属章节
     * @param count          生成题目数量
     * @param whitelist      白名单，每项 [code, name]（含目标的 1–2 层前置）
     * @return 生成的题目列表
     */
    java.util.List<AiQuestion> generate(String targetNodeCode, String targetName,
            String chapter, int count, java.util.List<String[]> whitelist);

    /**
     * 存量题标注：在白名单内标注该题考点；超纲返回 mainPoint=null。
     *
     * @param stem      题干
     * @param options   选项，每项 [key, text]
     * @param whitelist 全图白名单，每项 [code, name]
     * @return 标注结果
     */
    AiAnnotation annotate(String stem, java.util.List<String[]> options,
            java.util.List<String[]> whitelist);

    /**
     * 为学习路径某步生成一句「为什么学这个」说明（≤40 字）。失败/禁用抛 BusinessException。
     *
     * @param nodeName   该步知识点名称
     * @param roleLabel  角色（根因/卡点/前置）
     * @param targetName 路径目标（卡点）名称
     * @return 一句中文说明
     */
    String explainLearningStep(String nodeName, String roleLabel, String targetName);
}
