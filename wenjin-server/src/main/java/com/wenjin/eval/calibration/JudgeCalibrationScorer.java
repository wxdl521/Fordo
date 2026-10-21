package com.wenjin.eval.calibration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a labelled calibration CSV and measures judge/human agreement. Rows with empty
 * {@code human_score} are ignored. Agreement = |human - judge| &lt;= 1; bias = mean(judge - human) per dimension
 * (positive: judge is more lenient than the human).
 */
public final class JudgeCalibrationScorer {

    public static final int AGREEMENT_TOLERANCE = 1;

    public record Report(int labelled, int agreed, double agreementRate, Map<String, Double> biasByDimension) {}

    private JudgeCalibrationScorer() {
    }

    public static Report score(Path csv) {
        try {
            return score(Files.readString(csv, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read calibration CSV " + csv, e);
        }
    }

    public static Report score(String csvText) {
        List<List<String>> rows = CalibrationCsv.parse(csvText);
        if (rows.isEmpty()) {
            return new Report(0, 0, 0, Map.of());
        }
        List<String> header = rows.get(0);
        int dimIdx = header.indexOf("dimension");
        int judgeIdx = header.indexOf("judge_score");
        int humanIdx = header.indexOf("human_score");
        if (dimIdx < 0 || judgeIdx < 0 || humanIdx < 0) {
            throw new IllegalArgumentException("CSV missing dimension/judge_score/human_score columns");
        }
        int labelled = 0;
        int agreed = 0;
        Map<String, double[]> bias = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() <= Math.max(humanIdx, Math.max(dimIdx, judgeIdx))) {
                continue;
            }
            String human = r.get(humanIdx).trim();
            if (human.isEmpty()) {
                continue;
            }
            int h = Integer.parseInt(human);
            int j = Integer.parseInt(r.get(judgeIdx).trim());
            labelled++;
            if (Math.abs(h - j) <= AGREEMENT_TOLERANCE) {
                agreed++;
            }
            double[] acc = bias.computeIfAbsent(r.get(dimIdx), k -> new double[2]);
            acc[0] += j - h;
            acc[1]++;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        bias.forEach((k, v) -> out.put(k, v[0] / v[1]));
        return new Report(labelled, agreed, labelled == 0 ? 0 : (double) agreed / labelled, out);
    }
}
