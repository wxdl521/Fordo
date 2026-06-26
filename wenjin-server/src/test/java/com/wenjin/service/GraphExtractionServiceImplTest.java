package com.wenjin.service;

import com.wenjin.ai.ImageToMarkdownAiClient;
import com.wenjin.ai.SyllabusGraphAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.service.impl.GraphExtractionServiceImpl;
import com.wenjin.support.ImagePreprocessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphExtractionServiceImplTest {

    @Mock ImagePreprocessor pre;
    @Mock ImageToMarkdownAiClient vision;
    @Mock SyllabusGraphAiClient syllabus;
    @org.mockito.Mock com.wenjin.support.DocumentTextExtractor docExtractor;
    @InjectMocks GraphExtractionServiceImpl service;

    @Test
    void imageFlow_tilesEachAndJoinsMarkdown() {
        byte[] raw = {1, 2, 3};
        byte[] t1 = {9};
        byte[] t2 = {8};
        MockMultipartFile f = new MockMultipartFile("file", "a.png", "image/png", raw);
        when(pre.compressTiles(raw)).thenReturn(java.util.List.of(t1, t2));
        when(vision.toMarkdown(t1)).thenReturn("# part1");
        when(vision.toMarkdown(t2)).thenReturn("## part2");
        GraphImportRequest draft = new GraphImportRequest();
        when(syllabus.extract("# part1\n\n## part2")).thenReturn(draft);

        GraphImportRequest result = service.extract("C1", f);

        assertThat(result).isSameAs(draft);
        verify(pre).compressTiles(raw);
        verify(vision).toMarkdown(t1);
        verify(vision).toMarkdown(t2);
        verify(syllabus).extract("# part1\n\n## part2");
    }

    @Test
    void unsupportedType_throws() {
        MockMultipartFile f = new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1});
        assertThatThrownBy(() -> service.extract("C1", f)).isInstanceOf(BusinessException.class);
    }

    @Test
    void blankCourseCode_throws() {
        MockMultipartFile f = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        assertThatThrownBy(() -> service.extract("  ", f)).isInstanceOf(BusinessException.class);
    }

    @Test
    void docFlow_runsPipelineAndReturnsDraft() {
        org.springframework.mock.web.MockMultipartFile f =
                new org.springframework.mock.web.MockMultipartFile("file", "a.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[]{1});
        when(docExtractor.extract(f)).thenReturn("第1章 绪论");
        GraphImportRequest draft = new GraphImportRequest();
        when(syllabus.extract("第1章 绪论")).thenReturn(draft);

        GraphImportRequest result = service.extract("C1", f);

        assertThat(result).isSameAs(draft);
        verify(docExtractor).extract(f);
        verify(syllabus).extract("第1章 绪论");
    }
}
