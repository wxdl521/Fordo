package com.wenjin.dto;

import lombok.Data;
import java.util.List;

/**
 * 学情看板返回体。
 * <p>
 * 节点统计口径（mastered / weak / rate / avgEffectiveScore）均基于读时衰减后的有效等级与分值（含时间衰减），
 * 反映班级当前真实学情；{@link NodeStat#avgRawScore} 保留数据库原始均分供对照。
 */
@Data
public class DashboardVO {
    private Summary summary;
    private List<NodeStat> nodes;
    private List<WeakItem> weakRanking;

    @Data
    public static class Summary {
        /** 选课 roster 人数（student_course） */
        private int totalStudents;
        /** roster 内已完成固定卷或自适应诊断的去重人数 */
        private int diagnosedStudents;
        /**
         * 班级整体掌握率 = 已掌握格子数 / (roster × 生效节点数)；
         * 分母为 0 时为 0.0（不再可空）。
         */
        private Double classAvgRate;
        private int nodeCount;
    }

    @Data
    public static class NodeStat {
        private String nodeCode;
        private String name;
        private String chapter;
        /** 班级该节点原始掌握度均分（数据库 raw）；无数据 null */
        private Double avgRawScore;
        /** 班级该节点有效掌握度均分（含时间衰减）；衰减关闭时与 avgRawScore 相同 */
        private Double avgEffectiveScore;
        /** 向后兼容字段，等同 {@link #avgRawScore} */
        private Double avgScore;
        /** 有效等级=2 的学生人数（含时间衰减口径） */
        private int mastered;
        /** 有效等级=1 的学生人数（含时间衰减口径，含正在遗忘的原已掌握节点） */
        private int weak;
        /** roster − 该节点有 mastery 的选课学生数 */
        private int undiagnosed;
        /** mastered/(mastered+weak)，基于有效等级（含时间衰减）；无数据 null */
        private Double rate;
    }

    @Data
    public static class WeakItem {
        private String nodeCode;
        private String name;
        private String chapter;
        private int weak;
        private Double rate;
        private int masteredPct;       // mastered/roster *100
        private int weakPct;           // weak/roster *100
    }
}
