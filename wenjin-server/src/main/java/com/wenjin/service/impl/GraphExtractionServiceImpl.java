package com.wenjin.service.impl;

import com.wenjin.ai.ImageToMarkdownAiClient;
import com.wenjin.ai.SyllabusGraphAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.service.GraphExtractionService;
import com.wenjin.support.DocumentTextExtractor;
import com.wenjin.support.ImagePreprocessor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

@Service
public class GraphExtractionServiceImpl implements GraphExtractionService {

    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp", "bmp");
    private static final Set<String> DOC_EXT = Set.of("pdf", "docx");

    private final ImagePreprocessor imagePreprocessor;
    private final ImageToMarkdownAiClient imageToMarkdownAiClient;
    private final SyllabusGraphAiClient syllabusGraphAiClient;
    private final DocumentTextExtractor documentTextExtractor;

    public GraphExtractionServiceImpl(ImagePreprocessor imagePreprocessor,
                                      ImageToMarkdownAiClient imageToMarkdownAiClient,
                                      SyllabusGraphAiClient syllabusGraphAiClient,
                                      DocumentTextExtractor documentTextExtractor) {
        this.imagePreprocessor = imagePreprocessor;
        this.imageToMarkdownAiClient = imageToMarkdownAiClient;
        this.syllabusGraphAiClient = syllabusGraphAiClient;
        this.documentTextExtractor = documentTextExtractor;
    }

    @Override
    public GraphImportRequest extract(String courseCode, MultipartFile file) {
        if (!StringUtils.hasText(courseCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseCode 不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        String ext = extension(file.getOriginalFilename());
        String text;
        if (IMAGE_EXT.contains(ext)) {
            byte[] raw;
            try {
                raw = file.getBytes();
            } catch (IOException e) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "读取图片失败：" + e.getMessage());
            }
            // 竖长文档会被切成多片(保持宽度可读),逐片转写后拼接;短图即单片。
            StringBuilder sb = new StringBuilder();
            for (byte[] tile : imagePreprocessor.compressTiles(raw)) {
                String md = imageToMarkdownAiClient.toMarkdown(tile);
                if (StringUtils.hasText(md)) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
                    sb.append(md.trim());
                }
            }
            text = sb.toString();
        } else if (DOC_EXT.contains(ext)) {
            text = documentTextExtractor.extract(file);
        } else {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "暂不支持的文件类型：" + ext + "(支持图片 / .pdf / .docx)");
        }
        return syllabusGraphAiClient.extract(text);
    }

    static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
