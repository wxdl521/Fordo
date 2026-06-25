package com.wenjin.service.impl;

import com.wenjin.ai.ImageToMarkdownAiClient;
import com.wenjin.ai.SyllabusGraphAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.service.GraphExtractionService;
import com.wenjin.support.ImagePreprocessor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

@Service
public class GraphExtractionServiceImpl implements GraphExtractionService {

    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp", "bmp");

    private final ImagePreprocessor imagePreprocessor;
    private final ImageToMarkdownAiClient imageToMarkdownAiClient;
    private final SyllabusGraphAiClient syllabusGraphAiClient;

    public GraphExtractionServiceImpl(ImagePreprocessor imagePreprocessor,
                                      ImageToMarkdownAiClient imageToMarkdownAiClient,
                                      SyllabusGraphAiClient syllabusGraphAiClient) {
        this.imagePreprocessor = imagePreprocessor;
        this.imageToMarkdownAiClient = imageToMarkdownAiClient;
        this.syllabusGraphAiClient = syllabusGraphAiClient;
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
            byte[] jpeg = imagePreprocessor.compress(raw);
            text = imageToMarkdownAiClient.toMarkdown(jpeg);
        } else {
            throw new BusinessException(ResultCode.BAD_REQUEST, "暂不支持的文件类型：" + ext + "(当前支持图片)");
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
