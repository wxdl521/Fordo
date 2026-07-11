package com.wenjin.support;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * AI 作业上传文件持久化：按内容 SHA-256 落盘，request_json 只存 hash/size/文件名。
 * <p>禁止只存临时路径（容器重启/临时清理会导致 worker 读不到）。
 */
@Component
public class AiJobPayloadStore {

    private static final Logger log = LoggerFactory.getLogger(AiJobPayloadStore.class);

    private final Path root;

    public AiJobPayloadStore(
            @Value("${wenjin.jobs.payload-dir:./data/ai-job-payloads}") String payloadDir) {
        this.root = Path.of(payloadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 AI job payload 目录: " + this.root, e);
        }
    }

    /**
     * 持久化上传文件，返回可控元数据（写入 request_json）。
     */
    public Map<String, Object> store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "读取上传文件失败");
        }
        return storeBytes(bytes, file.getOriginalFilename(), file.getContentType());
    }

    public Map<String, Object> storeBytes(byte[] bytes, String originalFilename, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        String hash = sha256Hex(bytes);
        Path target = root.resolve(hash);
        try {
            if (!Files.exists(target)) {
                Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException e) {
            // 并发写入：若已存在则复用
            if (!Files.exists(target)) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "保存上传文件失败");
            }
        }
        String name = StringUtils.hasText(originalFilename) ? originalFilename : "upload.bin";
        return Map.of(
                "fileHash", hash,
                "fileSize", bytes.length,
                "fileName", name,
                "contentType", contentType == null ? "application/octet-stream" : contentType
        );
    }

    /** 按 hash 读取完整字节。 */
    public byte[] load(String fileHash) {
        Path target = resolveHash(fileHash);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "作业附件不可用");
        }
    }

    /** 按 hash 打开流（调用方负责关闭）。 */
    public InputStream open(String fileHash) {
        Path target = resolveHash(fileHash);
        try {
            return Files.newInputStream(target, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "作业附件不可用");
        }
    }

    /** 尽力删除（成功/终态后可调用；失败仅打日志）。 */
    public void deleteQuietly(String fileHash) {
        if (!StringUtils.hasText(fileHash)) {
            return;
        }
        try {
            Files.deleteIfExists(resolveHash(fileHash));
        } catch (Exception e) {
            log.debug("删除 job payload 失败 hash={}: {}", fileHash, e.getMessage());
        }
    }

    private Path resolveHash(String fileHash) {
        if (!StringUtils.hasText(fileHash) || !fileHash.matches("^[a-f0-9]{64}$")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无效的文件指纹");
        }
        Path target = root.resolve(fileHash).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无效的文件指纹");
        }
        return target;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes)).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
