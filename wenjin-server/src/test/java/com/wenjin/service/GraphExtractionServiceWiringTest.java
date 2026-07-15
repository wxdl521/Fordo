package com.wenjin.service;

import com.wenjin.ai.ImageToMarkdownAiClient;
import com.wenjin.ai.SyllabusGraphAiClient;
import com.wenjin.config.LimitsProperties;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.impl.GraphExtractionServiceImpl;
import com.wenjin.support.DocumentTextExtractor;
import com.wenjin.support.ImagePreprocessor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GraphExtractionServiceWiringTest {

    @Test
    void springContextWiresProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ImagePreprocessor.class, () -> mock(ImagePreprocessor.class));
            context.registerBean(ImageToMarkdownAiClient.class, () -> mock(ImageToMarkdownAiClient.class));
            context.registerBean(SyllabusGraphAiClient.class, () -> mock(SyllabusGraphAiClient.class));
            context.registerBean(DocumentTextExtractor.class, () -> mock(DocumentTextExtractor.class));
            context.registerBean(CourseAccessService.class, () -> mock(CourseAccessService.class));
            context.registerBean(LimitsProperties.class, LimitsProperties::new);
            context.registerBean(GraphExtractionServiceImpl.class);

            context.refresh();

            assertThat(context.getBean(GraphExtractionServiceImpl.class)).isNotNull();
        }
    }
}
