package com.wenjin.dto;

import lombok.Data;
import java.util.List;

/** 学情看板返回体。 */
@Data
public class DashboardVO {
    private Summary summary;
    private List<NodeStat> nodes;
    private List<WeakItem> weakRanking;

    @Data
    public static class Summary {
        private int totalStudents;
        private int diagnosedStudents;
        private Double classAvgRate;   // 全班整体掌握率（已诊断节点均分/100），可空
        private int nodeCount;
    }

    @Data
    public static class NodeStat {
        private String nodeCode;
        private String name;
        private String chapter;
        private Double avgScore;       // 无数据 null
        private int mastered;
        private int weak;
        private int undiagnosed;
        private Double rate;           // mastered/(mastered+weak)，无数据 null
    }

    @Data
    public static class WeakItem {
        private String nodeCode;
        private String name;
        private String chapter;
        private int weak;
        private Double rate;
        private int masteredPct;       // mastered/总学生 *100
        private int weakPct;           // weak/总学生 *100
    }
}
