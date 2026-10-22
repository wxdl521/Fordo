package com.wenjin.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.entity.ChunkNodeMap;
import com.wenjin.entity.DocChunk;
import com.wenjin.entity.DocSource;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.ChunkNodeMapMapper;
import com.wenjin.mapper.DocChunkMapper;
import com.wenjin.mapper.DocSourceMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.support.ChunkMapReviewStatus;
import com.wenjin.support.DocSourceKind;
import com.wenjin.support.DocSourceStatus;
import com.wenjin.support.DocumentTextExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 文件入库（COURSEWARE/TEXTBOOK）：各格式、自动初标、NODE_REF 分流、失败、meta_json 合法。 */
@ExtendWith(MockitoExtension.class)
class DocIngestDocumentTest {

    @Mock private DocSourceMapper sourceMapper;
    @Mock private DocChunkMapper chunkMapper;
    @Mock private ChunkNodeMapMapper mapMapper;
    @Mock private KgNodeMapper nodeMapper;

    private DocIngestService service;
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new DocIngestService(sourceMapper, chunkMapper, mapMapper, nodeMapper,
                new DocumentTextExtractor());
    }

    private void stubInserts() {
        when(chunkMapper.insert(any(DocChunk.class))).thenAnswer(inv -> {
            DocChunk c = inv.getArgument(0);
            c.setId(ids.incrementAndGet());
            return 1;
        });
    }

    private void stubSource(long id, int kind) {
        DocSource s = new DocSource();
        s.setId(id);
        s.setCourseId(10L);
        s.setKind(kind);
        s.setTitle("操作系统讲义");
        s.setStatus(DocSourceStatus.INGESTING);
        s.setCreatedBy(7L);
        when(sourceMapper.selectById(id)).thenReturn(s);
    }

    private void stubNodes() {
        when(nodeMapper.selectList(any())).thenReturn(List.of(node("N1", "死锁"), node("N2", "进程调度")));
    }

    private static KgNode node(String code, String name) {
        KgNode n = new KgNode();
        n.setNodeCode(code);
        n.setName(name);
        return n;
    }

    @Test
    @DisplayName("md：切块 + PENDING 映射（置信度<1），无命中块不建映射，READY")
    void markdown_labelsPending() {
        stubSource(1L, DocSourceKind.COURSEWARE);
        stubNodes();
        stubInserts();
        String md = "# 并发\n## 死锁\n死锁是指多个进程互相等待。\n"
                + "## 其他\n" + "与知识点无关的闲聊内容，".repeat(80);

        Map<String, Object> r = service.ingestDocument(1L, md.getBytes(StandardCharsets.UTF_8), "a.md");

        assertThat(r.get("status")).isEqualTo("READY");
        assertThat((Integer) r.get("chunkCount")).isGreaterThanOrEqualTo(1);
        assertThat((Integer) r.get("labeledChunks")).isGreaterThanOrEqualTo(1);
        assertThat((Integer) r.get("unlabeledChunks")).isGreaterThanOrEqualTo(1);
        ArgumentCaptor<ChunkNodeMap> cap = ArgumentCaptor.forClass(ChunkNodeMap.class);
        verify(mapMapper, org.mockito.Mockito.atLeastOnce()).insert(cap.capture());
        assertThat(cap.getAllValues()).allMatch(m ->
                m.getReviewStatus() == ChunkMapReviewStatus.PENDING
                        && "N1".equals(m.getNodeCode())
                        && m.getConfidence().doubleValue() > 0 && m.getConfidence().doubleValue() < 1);
        verify(sourceMapper).updateStatus(1L, DocSourceStatus.READY);
    }

    @Test
    @DisplayName("txt 入库成功")
    void txt_ok() {
        stubSource(2L, DocSourceKind.TEXTBOOK);
        stubNodes();
        stubInserts();
        Map<String, Object> r = service.ingestDocument(2L, "进程调度算法包括先来先服务。".getBytes(StandardCharsets.UTF_8), "a.txt");
        assertThat(r.get("status")).isEqualTo("READY");
        assertThat(r.get("kind")).isEqualTo("TEXTBOOK");
        verify(mapMapper).insert(any(ChunkNodeMap.class));
    }

    @Test
    @DisplayName("docx 入库成功")
    void docx_ok() throws Exception {
        stubSource(3L, DocSourceKind.COURSEWARE);
        stubNodes();
        stubInserts();
        byte[] docx;
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph p = d.createParagraph();
            p.createRun().setText("死锁 happens when processes wait for each other");
            d.write(out);
            docx = out.toByteArray();
        }
        Map<String, Object> r = service.ingestDocument(3L, docx, "a.docx");
        assertThat(r.get("status")).isEqualTo("READY");
        assertThat((Integer) r.get("chunkCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("pdf 入库成功")
    void pdf_ok() throws Exception {
        stubSource(4L, DocSourceKind.COURSEWARE);
        stubNodes();
        stubInserts();
        byte[] pdf;
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            d.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Process scheduling overview text");
                cs.endText();
            }
            d.save(out);
            pdf = out.toByteArray();
        }
        Map<String, Object> r = service.ingestDocument(4L, pdf, "a.pdf");
        assertThat(r.get("status")).isEqualTo("READY");
        assertThat((Integer) r.get("chunkCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("md 带 NODE_REF frontmatter：来源转 NODE_REF，走原路径（APPROVED、conf 1.000）")
    void markdownWithFrontmatter_routesToNodeRef() {
        stubSource(5L, DocSourceKind.COURSEWARE);
        stubNodes();
        stubInserts();
        String md = "---\nnode_code: N1\ntitle: 死锁\nsource_ref: s\n---\n## 概念\n死锁内容正文\n";
        Map<String, Object> r = service.ingestDocument(5L, md.getBytes(StandardCharsets.UTF_8), "n.md");
        assertThat(r.get("kind")).isEqualTo("NODE_REF");
        assertThat(r.get("status")).isEqualTo("READY");
        ArgumentCaptor<DocSource> src = ArgumentCaptor.forClass(DocSource.class);
        verify(sourceMapper, org.mockito.Mockito.atLeastOnce()).updateById(src.capture());
        assertThat(src.getAllValues().get(0).getKind()).isEqualTo(DocSourceKind.NODE_REF);
        ArgumentCaptor<ChunkNodeMap> cap = ArgumentCaptor.forClass(ChunkNodeMap.class);
        verify(mapMapper).insert(cap.capture());
        assertThat(cap.getValue().getReviewStatus()).isEqualTo(ChunkMapReviewStatus.APPROVED);
    }

    @Test
    @DisplayName("失败：未知扩展名 / 损坏 pdf / 空内容 → FAILED 且不写 chunk")
    void failures() {
        stubSource(6L, DocSourceKind.COURSEWARE);
        Map<String, Object> bad = service.ingestDocument(6L, new byte[]{1, 2}, "a.exe");
        assertThat(bad.get("status")).isEqualTo("FAILED");
        assertThat(bad.get("error")).isEqualTo("UNSUPPORTED_TYPE");

        Map<String, Object> corrupt = service.ingestDocument(6L, new byte[]{1, 2, 3}, "a.pdf");
        assertThat(corrupt.get("status")).isEqualTo("FAILED");
        assertThat(corrupt.get("error")).isEqualTo("EXTRACT_FAILED");

        Map<String, Object> blank = service.ingestDocument(6L, "   \n".getBytes(StandardCharsets.UTF_8), "a.txt");
        assertThat(blank.get("error")).isEqualTo("EXTRACT_FAILED");

        verify(chunkMapper, never()).insert(any(DocChunk.class));
        verify(sourceMapper, org.mockito.Mockito.times(3)).updateStatus(6L, DocSourceStatus.FAILED);
    }

    @Test
    @DisplayName("ingestSource 按 kind 分派；markFailed 置 FAILED")
    void ingestSourceDispatchAndMarkFailed() {
        stubSource(7L, DocSourceKind.NODE_REF);
        Map<String, Object> r = service.ingestSource(7L, new byte[0], "x.md");
        assertThat(r.get("error")).isEqualTo("EMPTY_PAYLOAD");
        service.markFailed(8L);
        verify(sourceMapper).updateStatus(8L, DocSourceStatus.FAILED);
    }

    @Test
    @DisplayName("meta_json 恒为合法 JSON 且 ≤500：超长路径、引号、反斜杠、emoji")
    void metaJson_alwaysValid() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String nasty = ("路径\"带\\引号😀").repeat(120);
        for (String json : List.of(
                DocIngestService.metaJson(nasty, nasty),
                DocIngestService.metaJson(nasty, "短"),
                DocIngestService.metaJson(null, null),
                DocIngestService.metaJson("a>b", "b"))) {
            assertThat(json.length()).isLessThanOrEqualTo(500);
            assertThat(om.readTree(json).has("path")).isTrue();
        }
        assertThat(om.readTree(DocIngestService.metaJson("a>b", "b")).get("path").asText()).isEqualTo("a>b");
    }
}
