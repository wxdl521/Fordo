package com.wenjin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "wenjin.companion.tools")
public class CompanionToolProperties {

    private boolean enabled = true;

    private int maxRounds = 2;

    /** 单轮对话工具总次数上限（含为 side-effect 预留的 1 次，见 ToolExecutionState） */
    private int maxToolCalls = 4;

    private long timeoutMs = 5000;

    private int maxResultChars = 2000;

    /** 练习意图路由：自然语言要题时强制 start_practice */
    private boolean intentRoutingEnabled = true;
}
