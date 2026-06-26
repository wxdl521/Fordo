package com.wenjin.service;

import com.wenjin.ai.ImageToMarkdownAiClient;
import com.wenjin.ai.SyllabusGraphAiClient;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.service.impl.GraphExtractionServiceImpl;
import com.wenjin.support.DocumentTextExtractor;
import com.wenjin.support.ImagePreprocessor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不依赖 Mockito 的切片管线验证(本机 byte-buddy 自挂载 agent 在内存压力下会失败,
 * 故改用手写测试替身,直接验证「逐片转写 + 拼接 + 跳过空片」)。
 */
class GraphExtractionServiceTilingTest {

    @Test
    void imageFlow_tilesEachTileAndJoinsMarkdownSkippingBlank() {
        byte[] t1 = {1};
        byte[] t2 = {2};
        byte[] t3 = {3};

        // 替身:切片器返回三片
        ImagePreprocessor pre = new ImagePreprocessor() {
            @Override
            public List<byte[]> compressTiles(byte[] raw) {
                return List.of(t1, t2, t3);
            }
        };

        // 替身:每片各自转写,中间一片返回空(应被跳过)
        List<byte[]> seen = new ArrayList<>();
        ImageToMarkdownAiClient vision = tile -> {
            seen.add(tile);
            if (tile == t1) return "# part1";
            if (tile == t2) return "   ";       // 空白片
            return "## part3";
        };

        // 替身:记录最终拼接文本
        String[] captured = new String[1];
        GraphImportRequest draft = new GraphImportRequest();
        SyllabusGraphAiClient syllabus = text -> {
            captured[0] = text;
            return draft;
        };

        GraphExtractionServiceImpl service =
                new GraphExtractionServiceImpl(pre, vision, syllabus, new DocumentTextExtractor());

        MockMultipartFile f = new MockMultipartFile("file", "doc.png", "image/png", new byte[]{9, 9});
        GraphImportRequest result = service.extract("C1", f);

        assertThat(result).isSameAs(draft);
        assertThat(seen).containsExactly(t1, t2, t3);             // 每片都送了视觉模型
        assertThat(captured[0]).isEqualTo("# part1\n\n## part3");  // 拼接且跳过空白片
    }
}
