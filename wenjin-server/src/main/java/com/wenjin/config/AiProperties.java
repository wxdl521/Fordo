package com.wenjin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 服务配置（wenjin.ai.*）。OpenAI 兼容（默认 DeepSeek）。
 *
 * <p>字段标 {@code volatile}：管理后台「系统配置」运行时改写（{@code com.wenjin.sysconfig}），
 * AI 客户端每次调用都重新读取，写入后其它线程立即可见。</p>
 */
@Data
@ConfigurationProperties(prefix = "wenjin.ai")
public class AiProperties {

    /** 是否启用 AI 调用 */
    private volatile boolean enabled = true;

    /** 服务基址，如 https://api.deepseek.com（不含 /v1） */
    private volatile String baseUrl;

    /** API Key（Bearer） */
    private volatile String apiKey;

    /** 模型名，如 deepseek-chat */
    private volatile String model;

    /** 采样温度 */
    private volatile Double temperature;

    /** 视觉模型(仅识图,OpenAI 兼容,可换任意更强模型) */
    private Vision vision = new Vision();

    /** 文本嵌入(RAG 向量检索;OpenAI 兼容 /embeddings,默认智谱 embedding-3) */
    private Embedding embedding = new Embedding();

    @lombok.Data
    public static class Embedding {
        private volatile boolean enabled = false;
        /** 到 /embeddings 之前的完整前缀,如智谱 https://open.bigmodel.cn/api/paas/v4 */
        private volatile String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
        private volatile String apiKey;
        private volatile String model = "embedding-3";
        /** 向量维度(请求 dimensions 并校验返回长度) */
        private volatile int dimension = 1024;
        /** 单次请求最多条数 */
        private volatile int batchSize = 16;
    }

    @lombok.Data
    public static class Vision {
        private volatile boolean enabled = true;
        /** 到 chat/completions 之前的完整前缀,如智谱 https://open.bigmodel.cn/api/paas/v4 */
        private volatile String baseUrl;
        private volatile String apiKey;
        private volatile String model;
    }
}
