package com.wenjin.service;

import com.wenjin.ai.GraphCleanAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.service.impl.GraphImportServiceImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GraphImportServiceImpl 单元测试：覆盖 Excel 解析、AI 清洗委托、规则清洗回退。
 */
@ExtendWith(MockitoExtension.class)
class GraphImportServiceImplTest {

    @Mock GraphService graphService;
    @Mock GraphCleanAiClient graphCleanAiClient;

    private GraphImportServiceImpl service() {
        return new GraphImportServiceImpl(graphService, graphCleanAiClient);
    }

    // ============================ 参数校验 ============================

    @Test
    @DisplayName("courseCode 为空时拒绝")
    void rejectsEmptyCourseCode() {
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);
        assertThatThrownBy(() -> service().importFromExcel("", file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("文件为空时拒绝")
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);
        assertThatThrownBy(() -> service().importFromExcel("C1", file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("null 文件时拒绝")
    void rejectsNullFile() {
        assertThatThrownBy(() -> service().importFromExcel("C1", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.BAD_REQUEST.getCode());
    }

    // ============================ Excel 解析 + AI 委托 ============================

    @Test
    @DisplayName("正常 Excel 解析节点和边后调用 AI 清洗再导入")
    void normalExcelImportCallsAiThenGraphService() throws IOException {
        byte[] excel = createTestExcel(
                List.of(List.of("KT01", "用例图", "第1章", "3", "是", "理解", "用例建模")),
                List.of(List.of("KT01", "KT02", "前置", ""))
        );

        GraphImportRequest cleaned = new GraphImportRequest();
        cleaned.setNodes(List.of(makeNode("KT01", "用例图")));
        cleaned.setEdges(List.of(makeEdge("KT01", "KT02", "前置")));

        when(graphCleanAiClient.clean(any(), any())).thenReturn(cleaned);
        when(graphService.importGraph(eq("C1"), any())).thenReturn(
                new GraphImportResult(1L, "C1", "课程", 1, 1));

        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excel);

        GraphImportResult result = service().importFromExcel("C1", file);

        assertThat(result.getNodeCount()).isEqualTo(1);
        verify(graphCleanAiClient).clean(any(), any());
        verify(graphService).importGraph(eq("C1"), any());
    }

    @Test
    @DisplayName("AI 清洗失败时回退到规则清洗并成功导入")
    void fallsBackToRuleCleanWhenAiFails() throws IOException {
        byte[] excel = createTestExcel(
                List.of(List.of("KT01", "用例图", "第1章", "3", "否", "理解", "")),
                List.of(List.of("KT01", "KT02", "依赖", ""))
        );

        when(graphCleanAiClient.clean(any(), any())).thenThrow(new RuntimeException("AI 不可用"));
        when(graphService.importGraph(eq("C1"), any())).thenReturn(
                new GraphImportResult(1L, "C1", "课程", 1, 1));

        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excel);

        GraphImportResult result = service().importFromExcel("C1", file);

        assertThat(result.getNodeCount()).isEqualTo(1);
        // AI 被调用但失败了
        verify(graphCleanAiClient).clean(any(), any());
        // 回退后仍然调用了 graphService
        verify(graphService).importGraph(eq("C1"), any());
    }

    @Test
    @DisplayName("AI 回退时规则清洗标准化关系类型（依赖→前置）")
    void fallbackCleanNormalizesRelationType() throws IOException {
        byte[] excel = createTestExcel(
                List.of(List.of("KT01", "A", "第1章", "3", "否", "", "")),
                List.of(List.of("KT01", "KT02", "依赖", ""))
        );

        when(graphCleanAiClient.clean(any(), any())).thenThrow(new RuntimeException("AI 不可用"));
        ArgumentCaptor<GraphImportRequest> captor = ArgumentCaptor.forClass(GraphImportRequest.class);
        when(graphService.importGraph(eq("C1"), captor.capture())).thenReturn(
                new GraphImportResult(1L, "C1", "课程", 1, 1));

        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excel);

        service().importFromExcel("C1", file);

        GraphImportRequest sent = captor.getValue();
        assertThat(sent.getEdges().get(0).getType()).isEqualTo("前置");
    }

    @Test
    @DisplayName("Excel 解析失败时抛 BAD_REQUEST")
    void rejectsInvalidExcelFormat() {
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "not a valid excel".getBytes());

        assertThatThrownBy(() -> service().importFromExcel("C1", file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("缺少边 Sheet 时仍能正常导入（边列表为空）")
    void importWithOnlyNodeSheet() throws IOException {
        byte[] excel = createSingleNodeSheetExcel();

        GraphImportRequest cleaned = new GraphImportRequest();
        cleaned.setNodes(List.of(makeNode("KT01", "A")));
        cleaned.setEdges(List.of());

        when(graphCleanAiClient.clean(any(), any())).thenReturn(cleaned);
        when(graphService.importGraph(eq("C1"), any())).thenReturn(
                new GraphImportResult(1L, "C1", "课程", 1, 0));

        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excel);

        GraphImportResult result = service().importFromExcel("C1", file);
        assertThat(result.getNodeCount()).isEqualTo(1);
        assertThat(result.getEdgeCount()).isEqualTo(0);
    }

    // ============================ 辅助方法 ============================

    private GraphImportRequest.NodeItem makeNode(String id, String name) {
        GraphImportRequest.NodeItem n = new GraphImportRequest.NodeItem();
        n.setId(id);
        n.setName(name);
        n.setChapter("章");
        n.setDifficulty(3);
        n.setIsKey(false);
        return n;
    }

    private GraphImportRequest.EdgeItem makeEdge(String src, String tgt, String type) {
        GraphImportRequest.EdgeItem e = new GraphImportRequest.EdgeItem();
        e.setSource(src);
        e.setTarget(tgt);
        e.setType(type);
        return e;
    }

    private byte[] createTestExcel(List<List<String>> nodeRows, List<List<String>> edgeRows) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet nodeSheet = wb.createSheet("节点");
            Row header1 = nodeSheet.createRow(0);
            String[] nodeHeaders = {"id", "name", "chapter", "difficulty", "is_key", "bloom", "description"};
            for (int i = 0; i < nodeHeaders.length; i++) {
                header1.createCell(i).setCellValue(nodeHeaders[i]);
            }
            for (int r = 0; r < nodeRows.size(); r++) {
                Row row = nodeSheet.createRow(r + 1);
                List<String> data = nodeRows.get(r);
                for (int c = 0; c < data.size(); c++) {
                    row.createCell(c).setCellValue(data.get(c));
                }
            }

            Sheet edgeSheet = wb.createSheet("边");
            Row header2 = edgeSheet.createRow(0);
            String[] edgeHeaders = {"source", "target", "type", "note"};
            for (int i = 0; i < edgeHeaders.length; i++) {
                header2.createCell(i).setCellValue(edgeHeaders[i]);
            }
            for (int r = 0; r < edgeRows.size(); r++) {
                Row row = edgeSheet.createRow(r + 1);
                List<String> data = edgeRows.get(r);
                for (int c = 0; c < data.size(); c++) {
                    row.createCell(c).setCellValue(data.get(c));
                }
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private byte[] createSingleNodeSheetExcel() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet nodeSheet = wb.createSheet("节点");
            Row header = nodeSheet.createRow(0);
            String[] headers = {"id", "name", "chapter", "difficulty", "is_key", "bloom", "description"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            Row row = nodeSheet.createRow(1);
            row.createCell(0).setCellValue("KT01");
            row.createCell(1).setCellValue("A");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }
}
