package com.wenjin.support;

import com.wenjin.common.BusinessException;
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

    private static byte[] buildDocx(String... paragraphs) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String p : paragraphs) {
                doc.createParagraph().createRun().setText(p);
            }
            doc.write(out);
            return out.toByteArray();
        }
    }
}
