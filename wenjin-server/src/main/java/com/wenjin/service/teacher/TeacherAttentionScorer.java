package com.wenjin.service.teacher;

import com.wenjin.service.teacher.TeacherAnalyticsModels.Reason;
import com.wenjin.support.multiagent.ReportAggregations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 学生「关注分」：纯函数、可解释。分数 = 各原因得分之和（原因独立叠加），原因文案随分数一起返回。
 *
 * <pre>
 * UNDIAGNOSED    无任何掌握记录                                 25
 * LOW_MASTERY    平均有效分 &lt; (薄弱线+掌握线)/2：
 *                  &lt; 薄弱线: 30 + 10·(薄弱线−avg)/薄弱线        (30..40)
 *                  否则:     30·(掌握线−avg)/(掌握线−薄弱线)       (15..30)
 * DROP           近 7 天某知识点有效分下降 &gt; 0.15（0–1 口径）：20 + min(10, (drop−0.15)·50)
 * NO_PRACTICE    从未作答 25；距上次作答 ≥ 14 天：20 + min(10, (天数−14)/2)
 * MANY_WRONG     近 7 天错题 ≥ 5：10 + min(10, 错题数−5)
 * REVIEW_BACKLOG 复习积压（曾掌握、有效分已跌破掌握线的知识点）≥ 3：10 + min(5, 积压数−3)
 * </pre>
 * 阈值（掌握线/薄弱线、7 天下降 0.15、14 天未练）取自 {@code MasteryDecayCalculator} 与 {@link ReportAggregations}。
 */
public final class TeacherAttentionScorer {

    public static final int MANY_WRONG_MIN = 5;
    public static final int BACKLOG_MIN = 3;

    private TeacherAttentionScorer() {
    }

    /**
     * @param avgEffective      已评估知识点的平均有效分（0–100）；null = 未诊断
     * @param drop              近 7 天最大单点下降（0–1 口径，仅 &gt; 阈值的才有意义；无则 0）
     * @param dropNodeName      下降最多的知识点名称
     * @param daysSincePractice 距上次作答的天数；null = 从未作答
     * @param wrong7            近 7 天错题数
     * @param backlog           复习积压数
     */
    public record Facts(long studentId,
                        String name,
                        Double avgEffective,
                        double drop,
                        String dropNodeName,
                        Integer daysSincePractice,
                        String lastPracticeAt,
                        int wrong7,
                        int backlog) {
    }

    public record Scored(Facts facts, int score, List<String> codes, List<String> reasons) {
        public boolean flagged() {
            return !codes.isEmpty();
        }
    }

    public static Scored score(Facts f, double masteredThreshold, double weakThreshold) {
        double total = 0;
        List<String> codes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        if (f.avgEffective() == null) {
            total += 25;
            codes.add(Reason.UNDIAGNOSED);
            reasons.add("尚未诊断（无掌握记录）");
        } else {
            double avg = f.avgEffective();
            double cutoff = (masteredThreshold + weakThreshold) / 2.0;
            if (avg < cutoff) {
                double pts;
                if (avg < weakThreshold) {
                    pts = 30 + 10 * (weakThreshold - avg) / Math.max(1e-9, weakThreshold);
                } else {
                    pts = 30 * (masteredThreshold - avg) / Math.max(1e-9, masteredThreshold - weakThreshold);
                }
                total += pts;
                codes.add(Reason.LOW_MASTERY);
                reasons.add("平均掌握 " + fmt(avg) + (avg < weakThreshold ? "（低于薄弱线）" : "（未达掌握线）"));
            }
        }

        if (f.drop() > ReportAggregations.DROP_THRESHOLD) {
            total += 20 + Math.min(10, (f.drop() - ReportAggregations.DROP_THRESHOLD) * 50);
            codes.add(Reason.DROP);
            String node = f.dropNodeName() == null ? "某知识点" : "「" + f.dropNodeName() + "」";
            reasons.add("近7天" + node + "掌握下降 " + fmt(f.drop() * 100) + " 分");
        }

        if (f.daysSincePractice() == null) {
            total += 25;
            codes.add(Reason.NO_PRACTICE);
            reasons.add("从未作答");
        } else if (f.daysSincePractice() >= ReportAggregations.NO_PRACTICE_DAYS) {
            total += 20 + Math.min(10, (f.daysSincePractice() - ReportAggregations.NO_PRACTICE_DAYS) / 2.0);
            codes.add(Reason.NO_PRACTICE);
            reasons.add("已 " + f.daysSincePractice() + " 天未作答");
        }

        if (f.wrong7() >= MANY_WRONG_MIN) {
            total += 10 + Math.min(10, f.wrong7() - MANY_WRONG_MIN);
            codes.add(Reason.MANY_WRONG);
            reasons.add("近7天错题 " + f.wrong7() + " 道");
        }

        if (f.backlog() >= BACKLOG_MIN) {
            total += 10 + Math.min(5, f.backlog() - BACKLOG_MIN);
            codes.add(Reason.REVIEW_BACKLOG);
            reasons.add("复习积压 " + f.backlog() + " 个知识点");
        }
        return new Scored(f, (int) Math.round(total), List.copyOf(codes), List.copyOf(reasons));
    }

    /** 分数降序；同分平均掌握低者在前（未诊断最后），再按学生 id。 */
    public static final Comparator<Scored> RANKING = Comparator
            .comparingInt(Scored::score).reversed()
            .thenComparing((Scored s) -> s.facts().avgEffective() == null ? Double.MAX_VALUE : s.facts().avgEffective())
            .thenComparingLong(s -> s.facts().studentId());

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
