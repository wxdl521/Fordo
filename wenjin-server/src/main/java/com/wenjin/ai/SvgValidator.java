package com.wenjin.ai;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * 预览 SVG 质量校验器：只挡「明显坏图」，不追求像素级。
 * 约定：节点圆必须带 class="wj-node"（与星点等其它 circle 区分）。
 */
public final class SvgValidator {

    static final double VIEW_W = 1480, VIEW_H = 740;
    static final double MARGIN = 60;             // 出界容差
    static final double MIN_NODE_GAP = 22;       // 节点中心最小间距
    static final double EDGE_TOLERANCE = 0.25;   // 边数允许 ±25%
    static final double MAX_OVERLAP_RATIO = 0.12;// 允许 12% 节点对过近

    private SvgValidator() {}

    public static List<String> validate(String svg, int expectedNodes, int expectedEdges, List<String> chapters) {
        List<String> issues = new ArrayList<>();
        if (svg == null || svg.isBlank()) {
            issues.add("SVG 为空");
            return issues;
        }
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) {}
                public void error(SAXParseException e) {}
                public void fatalError(SAXParseException e) {}
            });
            doc = b.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            issues.add("SVG 不是合法 XML：" + e.getMessage());
            return issues; // 无法继续
        }

        Element root = doc.getDocumentElement();
        if (root == null || !"svg".equalsIgnoreCase(root.getTagName())) {
            issues.add("根元素不是 <svg>");
            return issues;
        }
        checkViewBox(root.getAttribute("viewBox"), issues);

        NodeList circles = doc.getElementsByTagName("circle");
        NodeList texts = doc.getElementsByTagName("text");
        int lineCount = countNonDefs(doc, "line")
                      + countNonDefs(doc, "path");

        // 节点圆（class=wj-node）计数 + 坐标 + 重叠
        List<double[]> centers = new ArrayList<>();
        int oob = 0;
        for (int i = 0; i < circles.getLength(); i++) {
            Element c = (Element) circles.item(i);
            if (!hasClass(c, "wj-node")) continue;
            double cx = parseD(c.getAttribute("cx"));
            double cy = parseD(c.getAttribute("cy"));
            centers.add(new double[]{cx, cy});
            if (cx < -MARGIN || cx > VIEW_W + MARGIN || cy < -MARGIN || cy > VIEW_H + MARGIN) oob++;
        }
        if (centers.size() != expectedNodes) {
            issues.add("节点圆数量 " + centers.size() + "，应为 " + expectedNodes);
        }
        if (texts.getLength() < expectedNodes) {
            issues.add("文本标签数 " + texts.getLength() + " 少于节点数 " + expectedNodes);
        }
        if (expectedEdges > 0) {
            double lo = expectedEdges * (1 - EDGE_TOLERANCE);
            double hi = expectedEdges * (1 + EDGE_TOLERANCE) + 1;
            if (lineCount < lo || lineCount > hi) {
                issues.add("连线数 " + lineCount + " 偏离边数 " + expectedEdges);
            }
        }
        if (oob > 0) {
            issues.add(oob + " 个节点坐标越界");
        }

        int tooClose = 0, pairs = 0;
        for (int i = 0; i < centers.size(); i++) {
            for (int j = i + 1; j < centers.size(); j++) {
                pairs++;
                double dx = centers.get(i)[0] - centers.get(j)[0];
                double dy = centers.get(i)[1] - centers.get(j)[1];
                if (Math.sqrt(dx * dx + dy * dy) < MIN_NODE_GAP) tooClose++;
            }
        }
        if (pairs > 0 && (double) tooClose / pairs > MAX_OVERLAP_RATIO) {
            issues.add("过近节点对过多（" + tooClose + "/" + pairs + "），疑似压叠");
        }

        // 风格标记
        if (!svg.contains("stroke-dasharray")) issues.add("缺少虚线边（包含关系 stroke-dasharray）");
        if (!svg.contains("marker") && !svg.contains("arrow")) issues.add("缺少箭头标记（前置边）");

        // 章节标题
        String allText = collectText(texts);
        for (String ch : chapters) {
            if (ch != null && !ch.isBlank() && !allText.contains(ch)) {
                issues.add("缺少章节标题：" + ch);
            }
        }
        return issues;
    }

    private static void checkViewBox(String vb, List<String> issues) {
        if (vb == null || vb.isBlank()) {
            issues.add("缺少 viewBox");
            return;
        }
        String[] p = vb.trim().split("[\\s,]+");
        if (p.length != 4) {
            issues.add("viewBox 格式不对：" + vb);
            return;
        }
        try {
            double w = Double.parseDouble(p[2]);
            double h = Double.parseDouble(p[3]);
            double want = VIEW_W / VIEW_H;
            if (h <= 0 || Math.abs(w / h - want) > 0.2) {
                issues.add("viewBox 宽高比偏离 1480x740：" + vb);
            }
        } catch (NumberFormatException nfe) {
            issues.add("viewBox 含非数字：" + vb);
        }
    }

    private static boolean hasClass(Element e, String cls) {
        String c = e.getAttribute("class");
        if (c == null || c.isEmpty()) return false;
        for (String token : c.trim().split("\\s+")) {
            if (token.equals(cls)) return true;
        }
        return false;
    }

    private static double parseD(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return Double.NaN; }
    }

    private static String collectText(NodeList texts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.getLength(); i++) {
            sb.append(texts.item(i).getTextContent()).append("\n");
        }
        return sb.toString();
    }

    /** 统计指定标签名的元素数，排除 <defs> 子孙（如 marker 内的 path 不算边）。 */
    private static int countNonDefs(Document doc, String tagName) {
        int n = 0;
        NodeList list = doc.getElementsByTagName(tagName);
        for (int i = 0; i < list.getLength(); i++) {
            if (!isInsideDefs(list.item(i))) n++;
        }
        return n;
    }

    private static boolean isInsideDefs(Node node) {
        Node p = node.getParentNode();
        while (p != null) {
            if ("defs".equalsIgnoreCase(p.getNodeName())) return true;
            p = p.getParentNode();
        }
        return false;
    }
}
