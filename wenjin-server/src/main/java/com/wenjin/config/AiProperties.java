package com.wenjin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 服务配置（wenjin.ai.*）。OpenAI 兼容（默认 DeepSeek）。
 */
@Data
@ConfigurationProperties(prefix = "wenjin.ai")
public class AiProperties {

    /** 是否启用 AI 调用 */
    private boolean enabled = true;

    /** 服务基址，如 https://api.deepseek.com（不含 /v1） */
    private String baseUrl;

    /** API Key（Bearer） */
    private String apiKey;

    /** 模型名，如 deepseek-chat */
    private String model;

    /** 采样温度 */
    private Double temperature;

    /** 视觉模型(仅识图,OpenAI 兼容,可换任意更强模型) */
    private Vision vision = new Vision();

    @lombok.Data
    public static class Vision {
        private boolean enabled = true;
        /** 到 chat/completions 之前的完整前缀,如智谱 https://open.bigmodel.cn/api/paas/v4 */
        private String baseUrl;
        private String apiKey;
        private String model;
    }
}
