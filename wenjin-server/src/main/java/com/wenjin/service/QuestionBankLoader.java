package com.wenjin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.QuestionBankFile;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 题库种子文件读取器（工作目录 seed/ 下，与图谱种子同放一处）。
 * 抽成独立 @Component 是为了让 importBank 单测可对其打桩、避免真实文件 IO。
 */
@Component
public class QuestionBankLoader {

    /** 题库种子文件路径（相对工作目录；以 java -jar 从 wenjin-server 目录启动时可解析） */
    private static final String BANK_FILE = "seed/问津_软件工程题库_v0.1.json";

    private final ObjectMapper objectMapper;

    public QuestionBankLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取并解析题库种子文件（UTF-8）。找不到或解析失败抛 BusinessException。
     */
    public QuestionBankFile load() {
        Path path = Path.of(BANK_FILE);
        if (!Files.exists(path)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "题库文件读取失败: 未找到 " + BANK_FILE);
        }
        try (InputStream in = Files.newInputStream(path)) {
            return objectMapper.readValue(in, QuestionBankFile.class);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "题库文件读取失败: " + e.getMessage());
        }
    }
}
