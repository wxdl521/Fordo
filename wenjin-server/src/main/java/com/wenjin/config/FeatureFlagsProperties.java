package com.wenjin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 功能开关（wenjin.features.*）。
 * <p>开发/测试默认开启；生产由环境变量注入，缺省关闭。</p>
 *
 * <p>除 {@code evalEnabled}（由 {@code EvalConfiguration} 的 {@code @ConditionalOnProperty} 启动时决定）外，
 * 各开关调用点都是每次 {@code isXxx()} 现读，管理后台「系统配置」可运行时改写，故标 {@code volatile}。</p>
 */
@Data
@ConfigurationProperties(prefix = "wenjin.features")
public class FeatureFlagsProperties {

    /** 固定卷诊断（组卷冻结 + 精确交卷） */
    private volatile boolean fixedDiagnosticEnabled = true;

    /** 图谱对账/修复任务 */
    private volatile boolean graphReconcileEnabled = true;

    /** AI 异步作业派发 */
    private volatile boolean aiJobDispatchEnabled = true;

    /** Agent 执行轨迹埋点（agent_run / agent_step） */
    private volatile boolean agentTraceEnabled = true;

    /** 评测套件 / runner（M6；关闭时 EvalSuite 整体 skip） */
    private boolean evalEnabled = true;

    /** 伴侣长期记忆（M7；关闭时 PromptAssembler 以空 MEMORY 槽工作） */
    private volatile boolean memoryEnabled = true;

    /** RAG 语料检索（M8；关闭时 RETRIEVAL 空、search_materials 下架） */
    private volatile boolean ragEnabled = true;

    /**
     * RAG 向量 / 混合检索（R2；需 rag-enabled 同时为真）。关闭或无可用嵌入客户端时检索退回纯全文（与 M8 行为一致），
     * 入库后也不再派发 RAG_EMBED 作业。
     */
    private volatile boolean ragVectorEnabled = true;

    /**
     * 反思探针 / 换策略重讲（M9；关闭时不下发 probe_understanding、不自动探针）。
     */
    private volatile boolean reflectionEnabled = true;

    /**
     * 伴侣 LLM 调用（M9 紧急 kill switch；关闭时走 CompanionDegradeService 零 LLM 模板）。
     */
    private volatile boolean companionLlmEnabled = true;

    /**
     * 学习计划（M10；关闭时工具下架、scheduler 不跑、brief 隐藏计划段、ACTIVE 冻结不删）。
     */
    private volatile boolean planEnabled = true;

    /**
     * 多智能体周报 / 抽查（M11；默认关闭。关则零调度、报告 API 404）。
     */
    private volatile boolean multiAgentEnabled = false;

    /**
     * 主动陪伴（方向 4）：伴侣空会话 opener、事件型提示条、导航小红点。关闭时 /api/companion/opener|hints/** 返回 404。
     */
    private volatile boolean proactiveEnabled = true;

    /**
     * 编程题判分沙箱 / 伴侣 run_code_check（方向 6）。关闭或沙箱后端不可用时
     * {@code CodeExecutionService.available()} 为 false，执行请求一律 UNAVAILABLE。
     */
    private volatile boolean codeCheckEnabled = true;

    /**
     * 教师 AI 助教（方向 7）：/api/teacher/assistant/**。关闭时这些接口统一 404（前端提示功能未开启）。
     */
    private volatile boolean teacherAssistantEnabled = true;
}
