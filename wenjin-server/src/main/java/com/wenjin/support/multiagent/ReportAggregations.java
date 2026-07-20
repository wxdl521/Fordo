package com.wenjin.support.multiagent;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 教师周报确定性聚合（纯函数；无 DB / Clock / LLM）。
 * <p>
 * effective 口径为 0–1（规格字面量）；若上游为 0–100 分，调用方先除以 100。
 * 下降阈值 {@link #DROP_THRESHOLD} = 0.15；无练习窗口 {@link #NO_PRACTICE_DAYS} = 14。
 */
public final class ReportAggregations {

    /** 7 日 effective 下降超过此值 → 异常。 */
    public static final double DROP_THRESHOLD = 0.15;

    /** 连续无练习天数阈值。 */
    public static final int NO_PRACTICE_DAYS = 14;

    /** 班级弱点默认取最低 N 个节点。 */
    public static final int DEFAULT_WEAK_TOP_N = 5;

    private ReportAggregations() {}

    /**
     * 学生×节点当前 effective 快照。
     *
     * @param studentId 学生 id
     * @param nodeCode  节点业务编码
     * @param effective 有效掌握度 0–1
     */
    public record NodeStudentEffective(long studentId, String nodeCode, double effective) {
        public NodeStudentEffective {
            Objects.requireNonNull(nodeCode, "nodeCode");
        }
    }

    /**
     * 节点级班级弱点。
     *
     * @param nodeCode    节点编码
     * @param avgEffective 全班均值 0–1
     * @param sampleSize  参与均值的学生数
     */
    public record WeakNode(String nodeCode, double avgEffective, int sampleSize) {
        public WeakNode {
            Objects.requireNonNull(nodeCode, "nodeCode");
        }
    }

    /**
     * 7 日 effective 变化（before → after）。
     *
     * @param studentId      学生
     * @param nodeCode       节点
     * @param effectiveBefore 约 7 日前 effective 0–1
     * @param effectiveAfter  当前 effective 0–1
     */
    public record EffectiveDelta(
            long studentId,
            String nodeCode,
            double effectiveBefore,
            double effectiveAfter
    ) {
        public EffectiveDelta {
            Objects.requireNonNull(nodeCode, "nodeCode");
        }

        public double drop() {
            return effectiveBefore - effectiveAfter;
        }
    }

    /**
     * 学生最近练习日（null=从未练习）。
     */
    public record StudentLastPractice(long studentId, LocalDate lastPracticeDate) {}

    /**
     * 个体异常类型。
     */
    public enum AnomalyType {
        EFFECTIVE_DROP,
        NO_PRACTICE
    }

    /**
     * 个体异常。
     *
     * @param type      异常类型
     * @param studentId 学生
     * @param nodeCode  节点（无练习类可为空）
     * @param detail    数值细节（drop 量或距上次练习天数）
     */
    public record Anomaly(AnomalyType type, long studentId, String nodeCode, double detail) {
        public Anomaly {
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * 周参与度及环比。
     *
     * @param dialogueThisWeek  本周对话 run 数
     * @param dialogueLastWeek  上周对话 run 数
     * @param dialogueWowRatio  本周/上周；上周=0 且本周&gt;0 → +∞ 用 {@link Double#POSITIVE_INFINITY}；皆 0 → 1.0
     * @param practiceThisWeek  本周练习提交数
     * @param practiceLastWeek  上周练习提交数
     * @param practiceWowRatio  同上
     */
    public record Engagement(
            int dialogueThisWeek,
            int dialogueLastWeek,
            double dialogueWowRatio,
            int practiceThisWeek,
            int practiceLastWeek,
            double practiceWowRatio
    ) {}

    /**
     * 班级弱点：按节点求 avg effective，取最低 {@code topN}（升序；并列按 nodeCode）。
     */
    public static List<WeakNode> weakNodes(List<NodeStudentEffective> rows, int topN) {
        if (rows == null || rows.isEmpty() || topN <= 0) {
            return List.of();
        }
        Map<String, List<Double>> byNode = new HashMap<>();
        for (NodeStudentEffective row : rows) {
            if (row == null || row.nodeCode() == null || row.nodeCode().isBlank()) {
                continue;
            }
            byNode.computeIfAbsent(row.nodeCode(), k -> new ArrayList<>()).add(row.effective());
        }
        List<WeakNode> all = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : byNode.entrySet()) {
            List<Double> vals = e.getValue();
            double sum = 0;
            for (double v : vals) {
                sum += v;
            }
            double avg = sum / vals.size();
            all.add(new WeakNode(e.getKey(), avg, vals.size()));
        }
        all.sort(Comparator
                .comparingDouble(WeakNode::avgEffective)
                .thenComparing(WeakNode::nodeCode));
        if (all.size() <= topN) {
            return List.copyOf(all);
        }
        return List.copyOf(all.subList(0, topN));
    }

    /**
     * 个体异常：7 日 drop &gt; {@link #DROP_THRESHOLD}；连续 {@link #NO_PRACTICE_DAYS} 日无练习。
     *
     * @param asOf 判定「今天」（由调用方从 Clock 推导）
     */
    public static List<Anomaly> anomalies(
            List<EffectiveDelta> deltas,
            List<StudentLastPractice> practices,
            LocalDate asOf
    ) {
        Objects.requireNonNull(asOf, "asOf");
        List<Anomaly> out = new ArrayList<>();

        if (deltas != null) {
            for (EffectiveDelta d : deltas) {
                if (d == null) {
                    continue;
                }
                double drop = d.drop();
                if (drop > DROP_THRESHOLD) {
                    out.add(new Anomaly(AnomalyType.EFFECTIVE_DROP, d.studentId(), d.nodeCode(), drop));
                }
            }
        }

        if (practices != null) {
            for (StudentLastPractice p : practices) {
                if (p == null) {
                    continue;
                }
                // Spec: 连续 14 日无练习 → lastPractice 为空或距 asOf ≥ 14 天
                if (p.lastPracticeDate() == null) {
                    out.add(new Anomaly(AnomalyType.NO_PRACTICE, p.studentId(), null,
                            Double.POSITIVE_INFINITY));
                    continue;
                }
                long days = java.time.temporal.ChronoUnit.DAYS.between(p.lastPracticeDate(), asOf);
                if (days >= NO_PRACTICE_DAYS) {
                    out.add(new Anomaly(AnomalyType.NO_PRACTICE, p.studentId(), null, (double) days));
                }
            }
        }

        // 稳定排序：type → studentId → nodeCode
        out.sort(Comparator
                .comparing((Anomaly a) -> a.type().name())
                .thenComparingLong(Anomaly::studentId)
                .thenComparing(a -> a.nodeCode() == null ? "" : a.nodeCode()));
        return List.copyOf(out);
    }

    /**
     * 周活跃对话 / 练习提交数环比。
     * <p>上周为 0：本周也为 0 → ratio=1.0；本周&gt;0 → {@link Double#POSITIVE_INFINITY}。
     */
    public static Engagement engagement(
            int dialogueThisWeek,
            int dialogueLastWeek,
            int practiceThisWeek,
            int practiceLastWeek
    ) {
        return new Engagement(
                Math.max(0, dialogueThisWeek),
                Math.max(0, dialogueLastWeek),
                wowRatio(dialogueThisWeek, dialogueLastWeek),
                Math.max(0, practiceThisWeek),
                Math.max(0, practiceLastWeek),
                wowRatio(practiceThisWeek, practiceLastWeek)
        );
    }

    static double wowRatio(int thisWeek, int lastWeek) {
        int t = Math.max(0, thisWeek);
        int l = Math.max(0, lastWeek);
        if (l == 0) {
            return t == 0 ? 1.0 : Double.POSITIVE_INFINITY;
        }
        return (double) t / (double) l;
    }

    /**
     * 将弱点列表序列化为稳定 LinkedHashMap 列表（便于 JSON）。
     */
    public static List<Map<String, Object>> weakNodesAsMaps(List<WeakNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        return nodes.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeCode", n.nodeCode());
            m.put("avgEffective", n.avgEffective());
            m.put("sampleSize", n.sampleSize());
            return m;
        }).collect(Collectors.toList());
    }

    public static List<Map<String, Object>> anomaliesAsMaps(List<Anomaly> anomalies) {
        if (anomalies == null || anomalies.isEmpty()) {
            return List.of();
        }
        return anomalies.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", a.type().name());
            m.put("studentId", a.studentId());
            if (a.nodeCode() != null) {
                m.put("nodeCode", a.nodeCode());
            }
            m.put("detail", a.detail());
            return m;
        }).collect(Collectors.toList());
    }

    public static Map<String, Object> engagementAsMap(Engagement e) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (e == null) {
            return m;
        }
        m.put("dialogueThisWeek", e.dialogueThisWeek());
        m.put("dialogueLastWeek", e.dialogueLastWeek());
        m.put("dialogueWowRatio", finiteOrNull(e.dialogueWowRatio()));
        m.put("practiceThisWeek", e.practiceThisWeek());
        m.put("practiceLastWeek", e.practiceLastWeek());
        m.put("practiceWowRatio", finiteOrNull(e.practiceWowRatio()));
        return m;
    }

    private static Object finiteOrNull(double v) {
        if (Double.isInfinite(v)) {
            return null; // JSON 无 Infinity；前端可按 null 显示「— / 新增」
        }
        return v;
    }
}
