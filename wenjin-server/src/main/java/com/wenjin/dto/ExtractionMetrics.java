package com.wenjin.dto;

import lombok.Data;
import java.math.BigDecimal;

/** 抽取审核指标:节点与边各一套。 */
@Data
public class ExtractionMetrics {

    private MetricSet node = new MetricSet();
    private MetricSet edge = new MetricSet();

    @Data
    public static class MetricSet {
        private int aiCount;       // AI 产出数
        private int keptCount;     // 老师保留数
        private int deletedCount;  // 老师删除数 = ai - kept
        private int addedCount;    // 老师新增数
        private int modifiedCount; // 保留但字段被改数
        private int finalCount;    // 最终总数 = kept + added
        /** 召回率 kept/final,分母 0 为 null */
        private BigDecimal recall;
        /** 精确率 kept/ai,分母 0 为 null */
        private BigDecimal precision;
    }
}
