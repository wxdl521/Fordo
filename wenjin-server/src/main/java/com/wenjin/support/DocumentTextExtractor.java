package com.wenjin.support;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/** 文档文本抽取:.docx(POI)/.pdf(PDFBox)。无文本层 → 抛业务异常提示改用图片。 */
@Component
public class DocumentTextExtractor {

    public String extract(MultipartFile file) {
        String ext = extension(file.getOriginalFilename());
        String text;
        try (InputStream in = file.getInputStream()) {
            switch (ext) {
                case "docx" -> {
                    try (XWPFDocument doc = new XWPFDocument(in);
                         XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
                        text = ex.getText();
                    }
                }
                case "pdf" -> {
                    try (PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
                        text = new PDFTextStripper().getText(doc);
                    }
                }
                default -> throw new BusinessException(ResultCode.BAD_REQUEST,
                        "暂不支持的文档类型：" + ext + "(支持 .docx / .pdf)");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文档解析失败：" + e.getMessage());
        }
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "该文档无可提取文本(可能是扫描件),请改用图片入口");
        }
        return text;
    }

    static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
