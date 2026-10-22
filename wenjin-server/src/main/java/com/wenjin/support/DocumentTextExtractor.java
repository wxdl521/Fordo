package com.wenjin.support;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.LimitsProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 文档文本抽取:.docx(POI)/.pdf(PDFBox)。页数/解压比/文本量受资源预算约束。 */
@Component
public class DocumentTextExtractor {

    private final LimitsProperties limits;

    @Autowired
    public DocumentTextExtractor(LimitsProperties limits) {
        this.limits = limits == null ? new LimitsProperties() : limits;
    }

    public DocumentTextExtractor() {
        this(new LimitsProperties());
    }

    public String extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        String ext = extension(file.getOriginalFilename());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "读取文档失败：" + e.getMessage());
        }
        return extract(bytes, ext);
    }

    /** 便于单测直接传字节。 */
    public String extract(byte[] bytes, String ext) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        String text;
        try {
            switch (ext == null ? "" : ext.toLowerCase()) {
                case "docx" -> {
                    assertDecompressRatio(bytes);
                    try (InputStream in = new ByteArrayInputStream(bytes);
                         XWPFDocument doc = new XWPFDocument(in);
                         XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
                        text = ex.getText();
                    }
                }
                case "pdf" -> {
                    try (PDDocument doc = Loader.loadPDF(bytes)) {
                        int pages = doc.getNumberOfPages();
                        int maxPages = limits.getDocument().getMaxPages();
                        if (pages > maxPages) {
                            throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE,
                                    "PDF 页数超限：" + pages + " > " + maxPages);
                        }
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
        int maxChars = limits.getDocument().getMaxTextChars();
        if (text.length() > maxChars) {
            throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE,
                    "文档文本超限：" + text.length() + " > " + maxChars);
        }
        return text;
    }

    /**
     * DOCX 为 zip：累计未压缩条目大小 / 压缩包大小，超比则 413。
     */
    void assertDecompressRatio(byte[] bytes) {
        int maxRatio = Math.max(1, limits.getDocument().getMaxDecompressRatio());
        long compressed = bytes.length;
        if (compressed <= 0) {
            return;
        }
        long uncompressed = 0L;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                long declared = entry.getSize();
                if (declared > 0) {
                    uncompressed += declared;
                } else {
                    int n;
                    long entryBytes = 0;
                    while ((n = zis.read(buf)) > 0) {
                        entryBytes += n;
                        if (entryBytes > (long) maxRatio * compressed) {
                            throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE,
                                    "文档解压膨胀比超限（>" + maxRatio + "x）");
                        }
                    }
                    uncompressed += entryBytes;
                }
                if (uncompressed > (long) maxRatio * compressed) {
                    throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE,
                            "文档解压膨胀比超限（>" + maxRatio + "x）");
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文档解析失败：" + e.getMessage());
        }
    }

    /** md/txt：UTF-8 解码（去 BOM），空文本与超长文本同样受限。 */
    public String decodePlainText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件内容为空");
        }
        int maxChars = limits.getDocument().getMaxTextChars();
        if (text.length() > maxChars) {
            throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE,
                    "文档文本超限：" + text.length() + " > " + maxChars);
        }
        return text;
    }

    public static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
