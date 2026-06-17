package com.wenjin.service;

import com.wenjin.dto.GraphImportResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图谱 Excel 导入服务：解析 Excel → AI 清洗 → 调用 GraphService 导入。
 */
public interface GraphImportService {

    /**
     * 从 Excel 文件导入图谱。
     * <p>
     * 流程：解析 Excel（Sheet 1 节点 + Sheet 2 边）→ AI 清洗 → graphService.importGraph()。
     *
     * @param courseCode 课程业务编码
     * @param file       上传的 Excel 文件（.xlsx）
     * @return 导入摘要
     */
    GraphImportResult importFromExcel(String courseCode, MultipartFile file);
}
