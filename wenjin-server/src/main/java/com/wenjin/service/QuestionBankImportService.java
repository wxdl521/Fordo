package com.wenjin.service;

import com.wenjin.dto.ImportBankResult;
import com.wenjin.dto.QuestionBankFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 题库导入服务（JSON / Excel 上传）。
 */
public interface QuestionBankImportService {

    /**
     * 从 JSON 文件导入题库。
     *
     * @param courseId 课程 ID
     * @param bank     已解析的题库文件
     * @return 导入结果
     */
    ImportBankResult importFromJson(Long courseId, QuestionBankFile bank);

    /**
     * 从 Excel 文件导入题库（含 AI 清洗）。
     *
     * @param courseId 课程 ID
     * @param file     上传的 Excel 文件
     * @return 导入结果
     */
    ImportBankResult importFromExcel(Long courseId, MultipartFile file);
}
