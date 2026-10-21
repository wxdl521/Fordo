package com.wenjin.eval.calibration;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 CSV helpers (quoted fields, embedded commas/quotes/newlines). */
final class CalibrationCsv {

    private CalibrationCsv() {
    }

    static String field(String s) {
        String v = s == null ? "" : s;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cur.toString());
                cur.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cur.toString());
                cur.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0 || !row.isEmpty()) {
            row.add(cur.toString());
            rows.add(row);
        }
        return rows;
    }
}
