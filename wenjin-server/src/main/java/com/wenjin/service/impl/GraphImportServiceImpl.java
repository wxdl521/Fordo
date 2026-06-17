package com.wenjin.service.impl;

import com.wenjin.ai.GraphCleanAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.service.GraphImportService;
import com.wenjin.service.GraphService;
import com.wenjin.support.RelationType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱 Excel 导入服务实现。
 * <p>
 * 流程：解析 Excel（Sheet 1 节点 + Sheet 2 边）→ AI 清洗 → graphService.importGraph()。
 * AI 不可用时回退到规则清洗。
 */
@Service
public class GraphImportServiceImpl implements GraphImportService {

    private static final Logger log = LoggerFactory.getLogger(GraphImportServiceImpl.class);

    /** 节点表头列名 -> 索引映射 */
    private static final String[] NODE_HEADERS = {"id", "name", "chapter", "difficulty", "is_key", "bloom", "description"};
    /** 边表头列名 -> 索引映射 */
    private static final String[] EDGE_HEADERS = {"source", "target", "type", "note"};

    private final GraphService graphService;
    private final GraphCleanAiClient graphCleanAiClient;

    public GraphImportServiceImpl(GraphService graphService, GraphCleanAiClient graphCleanAiClient) {
        this.graphService = graphService;
        this.graphCleanAiClient = graphCleanAiClient;
    }

    @Override
    public GraphImportResult importFromExcel(String courseCode, MultipartFile file) {
        if (!StringUtils.hasText(courseCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseCode 不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }

        // 1) 解析 Excel
        ParsedData parsed;
        try {
            parsed = parseExcel(file);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 解析失败：" + e.getMessage());
        }

        if (parsed.nodes.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 解析结果为空：节点表无数据行");
        }

        // 2) AI 清洗（失败时回退到规则清洗）
        GraphImportRequest request;
        try {
            request = graphCleanAiClient.clean(parsed.nodes, parsed.edges);
            log.info("AI 清洗完成：节点={}, 边={}", request.getNodes().size(),
                    request.getEdges() == null ? 0 : request.getEdges().size());
        } catch (Exception e) {
            log.warn("AI 清洗失败，回退到规则清洗：{}", e.getMessage());
            request = ruleBasedClean(parsed.nodes, parsed.edges);
        }

        // 3) 委托 GraphService 导入
        return graphService.importGraph(courseCode, request);
    }

    // ============================ Excel 解析 ============================

    /**
     * 解析 Excel 文件为原始节点/边列表。
     * Sheet 1 "节点"：id, name, chapter, difficulty, is_key, bloom, description
     * Sheet 2 "边"：source, target, type, note
     * 第一行为表头。
     */
    ParsedData parseExcel(MultipartFile file) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            ParsedData result = new ParsedData();

            // Sheet 1：节点
            Sheet nodeSheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (nodeSheet == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 缺少第一个 Sheet（节点表）");
            }
            result.nodes = parseNodeSheet(nodeSheet);

            // Sheet 2：边（可选）
            if (workbook.getNumberOfSheets() > 1) {
                Sheet edgeSheet = workbook.getSheetAt(1);
                result.edges = parseEdgeSheet(edgeSheet);
            } else {
                result.edges = new ArrayList<>();
            }

            return result;
        }
    }

    private List<GraphImportRequest.NodeItem> parseNodeSheet(Sheet sheet) {
        List<GraphImportRequest.NodeItem> nodes = new ArrayList<>();
        Map<String, Integer> headerMap = parseHeader(sheet, NODE_HEADERS);

        for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isEmptyRow(row)) {
                continue;
            }

            GraphImportRequest.NodeItem node = new GraphImportRequest.NodeItem();
            node.setId(getCellString(row, headerMap.get("id")));
            node.setName(getCellString(row, headerMap.get("name")));
            node.setChapter(getCellString(row, headerMap.get("chapter")));
            node.setBloom(getCellString(row, headerMap.get("bloom")));
            node.setDescription(getCellString(row, headerMap.get("description")));

            // difficulty: 整数
            Integer diff = getCellInteger(row, headerMap.get("difficulty"));
            node.setDifficulty(diff);

            // is_key: 布尔
            node.setIsKey(getCellBoolean(row, headerMap.get("is_key")));

            nodes.add(node);
        }
        return nodes;
    }

    private List<GraphImportRequest.EdgeItem> parseEdgeSheet(Sheet sheet) {
        List<GraphImportRequest.EdgeItem> edges = new ArrayList<>();
        Map<String, Integer> headerMap = parseHeader(sheet, EDGE_HEADERS);

        for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isEmptyRow(row)) {
                continue;
            }

            GraphImportRequest.EdgeItem edge = new GraphImportRequest.EdgeItem();
            edge.setSource(getCellString(row, headerMap.get("source")));
            edge.setTarget(getCellString(row, headerMap.get("target")));
            edge.setType(getCellString(row, headerMap.get("type")));
            edge.setNote(getCellString(row, headerMap.get("note")));

            edges.add(edge);
        }
        return edges;
    }

    /**
     * 解析表头行，返回列名 -> 列索引的映射。
     * 表头不区分大小写，去除首尾空格。
     */
    private Map<String, Integer> parseHeader(Sheet sheet, String[] expectedHeaders) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 第一行（表头）为空");
        }

        Map<String, Integer> headerMap = new HashMap<>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null) {
                String header = getCellValue(cell).trim().toLowerCase();
                if (StringUtils.hasText(header)) {
                    headerMap.put(header, c);
                }
            }
        }

        // 检查必须的列是否存在
        for (String required : expectedHeaders) {
            if (!headerMap.containsKey(required)) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "Excel 表头缺少必需列：「" + required + "」");
            }
        }
        return headerMap;
    }

    // ============================ 规则清洗（AI 不可用时的回退） ============================

    /**
     * 规则清洗：不调用 AI，仅做格式标准化。
     */
    GraphImportRequest ruleBasedClean(List<GraphImportRequest.NodeItem> rawNodes,
                                      List<GraphImportRequest.EdgeItem> rawEdges) {
        GraphImportRequest request = new GraphImportRequest();

        // 节点清洗
        List<GraphImportRequest.NodeItem> nodes = new ArrayList<>();
        int autoId = 1;
        for (GraphImportRequest.NodeItem n : rawNodes) {
            GraphImportRequest.NodeItem cleaned = new GraphImportRequest.NodeItem();
            // 空 id 自动生成
            cleaned.setId(StringUtils.hasText(n.getId()) ? n.getId().trim() : "AUTO_" + (autoId++));
            cleaned.setName(n.getName());
            cleaned.setChapter(n.getChapter());
            // difficulty 标准化：1-5，缺失或越界补 3
            cleaned.setDifficulty(normalizeDifficulty(n.getDifficulty()));
            // is_key 标准化
            cleaned.setIsKey(n.getIsKey() != null && n.getIsKey());
            // bloom 保留（AI 不可用时无法推断）
            cleaned.setBloom(n.getBloom());
            cleaned.setDescription(n.getDescription());
            cleaned.setNote(n.getNote());
            nodes.add(cleaned);
        }
        request.setNodes(nodes);

        // 边清洗
        Set<String> nodeIds = new HashSet<>();
        for (GraphImportRequest.NodeItem n : nodes) {
            nodeIds.add(n.getId());
        }

        List<GraphImportRequest.EdgeItem> edges = new ArrayList<>();
        if (rawEdges != null) {
            for (GraphImportRequest.EdgeItem e : rawEdges) {
                GraphImportRequest.EdgeItem cleaned = new GraphImportRequest.EdgeItem();
                cleaned.setSource(StringUtils.hasText(e.getSource()) ? e.getSource().trim() : null);
                cleaned.setTarget(StringUtils.hasText(e.getTarget()) ? e.getTarget().trim() : null);
                // 关系类型标准化
                cleaned.setType(normalizeRelationType(e.getType()));
                cleaned.setNote(e.getNote());
                edges.add(cleaned);
            }
        }
        request.setEdges(edges);

        log.info("规则清洗完成：节点={}, 边={}", nodes.size(), edges.size());
        return request;
    }

    /**
     * 标准化关系类型：常见同义词映射到 RelationType 的四种标准标签。
     */
    private String normalizeRelationType(String type) {
        if (!StringUtils.hasText(type)) {
            return type;
        }
        String trimmed = type.trim();
        // 先检查是否已经是合法类型
        if (RelationType.fromLabel(trimmed) != null) {
            return trimmed;
        }
        // 同义词映射
        return switch (trimmed) {
            case "依赖", "前置关系", "先修", "预备", "prerequisite" -> "前置";
            case "属于", "包含关系", "隶属", "从属", "contains" -> "包含";
            case "关联", "相关关系", "联系", "related" -> "相关";
            case "对应", "应用关系", "适用于", "applies" -> "应用";
            default -> trimmed;
        };
    }

    /**
     * 标准化难度：确保 1-5 范围内。
     */
    private Integer normalizeDifficulty(Integer difficulty) {
        if (difficulty == null || difficulty < 1 || difficulty > 5) {
            return 3;
        }
        return difficulty;
    }

    // ============================ 工具方法 ============================

    private String getCellString(Row row, Integer colIndex) {
        if (colIndex == null) return null;
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;
        String value = getCellValue(cell).trim();
        return StringUtils.hasText(value) ? value : null;
    }

    private Integer getCellInteger(Row row, Integer colIndex) {
        if (colIndex == null) return null;
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.NUMERIC) {
            return (int) cell.getNumericCellValue();
        }
        if (type == CellType.STRING) {
            String s = cell.getStringCellValue().trim();
            if (StringUtils.hasText(s)) {
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private Boolean getCellBoolean(Row row, Integer colIndex) {
        if (colIndex == null) return null;
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.BOOLEAN) {
            return cell.getBooleanCellValue();
        }
        if (type == CellType.NUMERIC) {
            return cell.getNumericCellValue() != 0;
        }
        if (type == CellType.STRING) {
            String s = cell.getStringCellValue().trim().toLowerCase();
            return "是".equals(s) || "true".equals(s) || "1".equals(s) || "y".equals(s);
        }
        return null;
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType();
        if (type == CellType.STRING) {
            return cell.getStringCellValue();
        }
        if (type == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        if (type == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return "";
    }

    private boolean isEmptyRow(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String v = getCellValue(cell).trim();
                if (StringUtils.hasText(v)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 解析后的 Excel 数据中间结构 */
    static class ParsedData {
        List<GraphImportRequest.NodeItem> nodes = new ArrayList<>();
        List<GraphImportRequest.EdgeItem> edges = new ArrayList<>();
    }
}
