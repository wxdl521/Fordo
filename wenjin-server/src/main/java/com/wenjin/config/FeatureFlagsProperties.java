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
}
