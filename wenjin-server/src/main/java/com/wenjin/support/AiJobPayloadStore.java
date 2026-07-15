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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        Path temp = null;
        try {
            if (hasExpectedContent(target, bytes.length, hash)) {
                return metadata(hash, bytes.length, originalFilename, contentType);
            }

            temp = Files.createTempFile(root, hash + ".", ".tmp");
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            publish(temp, target);
            temp = null;
        } catch (IOException e) {
            if (!hasExpectedContent(target, bytes.length, hash)) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "保存上传文件失败");
            }
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.debug("Failed to delete temporary job payload {}: {}", temp, e.getMessage());
                }
            }
        }
        return metadata(hash, bytes.length, originalFilename, contentType);
    }

    private void publish(Path temp, Path target) throws IOException {
        moveAtomically(temp, target);
    }

    void moveAtomically(Path temp, Path target) throws IOException {
        Files.move(temp, target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private boolean hasExpectedContent(Path target, int expectedSize, String expectedHash) {
        try {
            if (!Files.isRegularFile(target) || Files.size(target) != expectedSize) {
                return false;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(target, StandardOpenOption.READ)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            String actualHash = HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
            return expectedHash.equals(actualHash);
        } catch (IOException e) {
            return false;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Map<String, Object> metadata(
            String hash, int fileSize, String originalFilename, String contentType) {
        String name = StringUtils.hasText(originalFilename) ? originalFilename : "upload.bin";
        return Map.of(
                "fileHash", hash,
                "fileSize", fileSize,
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
        deleteAndConfirmAbsent(fileHash);
    }

    /** Returns true only when the final payload path is confirmed absent after the delete attempt. */
    public boolean deleteAndConfirmAbsent(String fileHash) {
        if (!StringUtils.hasText(fileHash)) {
            return false;
        }
        try {
            Path target = resolveHash(fileHash);
            Files.deleteIfExists(target);
            return Files.notExists(target);
        } catch (Exception e) {
            log.debug("删除 job payload 失败 hash={}: {}", fileHash, e.getMessage());
            return false;
        }
    }

    /** Deletes a bounded batch of old crash leftovers that have no durable database reference. */
    public int deleteOldUnreferencedFiles(Set<String> referencedHashes, Instant before, int limit) {
        if (before == null) {
            return 0;
        }
        Set<String> referenced = referencedHashes == null ? Set.of() : referencedHashes;
        int maxDeletes = Math.max(1, limit);
        int deleted = 0;
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (deleted >= maxDeletes) {
                    break;
                }
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                boolean finalPayload = name.matches("^[a-f0-9]{64}$");
                boolean temporaryPayload = name.matches("^[a-f0-9]{64}\\..+\\.tmp$");
                if ((!finalPayload && !temporaryPayload)
                        || (finalPayload && referenced.contains(name))) {
                    continue;
                }
                try {
                    if (!Files.getLastModifiedTime(entry).toInstant().isBefore(before)) {
                        continue;
                    }
                    if (Files.deleteIfExists(entry)) {
                        deleted++;
                    }
                } catch (IOException e) {
                    log.debug("Failed to delete stale job payload {}: {}", entry, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan AI job payload directory {}: {}", root, e.getMessage());
        }
        return deleted;
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
