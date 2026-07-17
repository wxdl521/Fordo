package com.wenjin.support;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.LimitsProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void extract_docx_returnsText() throws Exception {
        byte[] docx = buildDocx("第1章 绪论", "1.1 软件危机");
        MockMultipartFile f = new MockMultipartFile("file", "a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);

        String text = extractor.extract(f);

        assertThat(text).contains("第1章 绪论").contains("软件危机");
    }

    @Test
    void extract_emptyDocx_throws() throws Exception {
        byte[] docx = buildDocx();
        MockMultipartFile f = new MockMultipartFile("file", "a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);

        assertThatThrownBy(() -> extractor.extract(f)).isInstanceOf(BusinessException.class);
    }

    @Test
    void extract_unsupported_throws() {
        MockMultipartFile f = new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1});
        assertThatThrownBy(() -> extractor.extract(f)).isInstanceOf(BusinessException.class);
    }

    @Test
    void extract_pdfPagesOverLimit_413() throws Exception {
        LimitsProperties limits = new LimitsProperties();
        limits.getDocument().setMaxPages(2);
        DocumentTextExtractor limited = new DocumentTextExtractor(limits);
        byte[] pdf = buildPdf(3);
        MockMultipartFile f = new MockMultipartFile("file", "a.pdf", "application/pdf", pdf);

        assertThatThrownBy(() -> limited.extract(f))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.PAYLOAD_TOO_LARGE.getCode());
    }

    @Test
    void extract_textOverLimit_413() throws Exception {
        LimitsProperties limits = new LimitsProperties();
        limits.getDocument().setMaxTextChars(10);
        DocumentTextExtractor limited = new DocumentTextExtractor(limits);
        byte[] docx = buildDocx("这是一段超过十个字符的正文内容");
        MockMultipartFile f = new MockMultipartFile("file", "a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);

        assertThatThrownBy(() -> limited.extract(f))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.PAYLOAD_TOO_LARGE.getCode());
    }

    private static byte[] buildDocx(String... paragraphs) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String p : paragraphs) {
                doc.createParagraph().createRun().setText(p);
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildPdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
