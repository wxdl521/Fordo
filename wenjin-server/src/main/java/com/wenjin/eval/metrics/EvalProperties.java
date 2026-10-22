package com.wenjin.eval.metrics;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 评测配置（wenjin.eval.*）。
 */
@Data
@ConfigurationProperties(prefix = "wenjin.eval")
public class EvalProperties {

    /** LLM-as-judge 模型；空则回落 wenjin.ai.model */
    private String judgeModel;

    private Price price = new Price();

    @Data
    public static class Price {
        /** 每百万输入 token 单价；0 表示不计成本 */
        private double inputPerMtok = 0;
        /** 每百万输出 token 单价；0 表示不计成本 */
        private double outputPerMtok = 0;
    }
}
