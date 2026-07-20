package com.wenjin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 功能开关（wenjin.features.*）。
 * <p>开发/测试默认开启；生产由环境变量注入，缺省关闭。</p>
 */
@Data
@ConfigurationProperties(prefix = "wenjin.features")
public class FeatureFlagsProperties {

    /** 固定卷诊断（组卷冻结 + 精确交卷） */
    private boolean fixedDiagnosticEnabled = true;

    /** 图谱对账/修复任务 */
    private boolean graphReconcileEnabled = true;

    /** AI 异步作业派发 */
    private boolean aiJobDispatchEnabled = true;

    /** Agent 执行轨迹埋点（agent_run / agent_step） */
    private boolean agentTraceEnabled = true;

    /** 评测套件 / runner（M6；关闭时 EvalSuite 整体 skip） */
    private boolean evalEnabled = true;

    /** 伴侣长期记忆（M7；关闭时 PromptAssembler 以空 MEMORY 槽工作） */
    private boolean memoryEnabled = true;

    /** RAG 语料检索（M8；关闭时 RETRIEVAL 空、search_materials 下架） */
    private boolean ragEnabled = true;

    /**
     * 反思探针 / 换策略重讲（M9；关闭时不下发 probe_understanding、不自动探针）。
     */
    private boolean reflectionEnabled = true;

    /**
     * 伴侣 LLM 调用（M9 紧急 kill switch；关闭时走 CompanionDegradeService 零 LLM 模板）。
     */
    private boolean companionLlmEnabled = true;
}
