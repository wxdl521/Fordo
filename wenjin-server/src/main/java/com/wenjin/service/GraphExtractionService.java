package com.wenjin.service;

import com.wenjin.dto.GraphImportRequest;
import org.springframework.web.multipart.MultipartFile;

/** 从上传文件(图片/文档)抽取图谱草稿,不落库。 */
public interface GraphExtractionService {
    GraphImportRequest extract(String courseCode, MultipartFile file);
}
