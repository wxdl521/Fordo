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
}
