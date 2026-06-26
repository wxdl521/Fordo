package com.wenjin.support;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 图片预处理。
 * <p>{@link #compress} 把单张图降采样到最长边 ≤ MAX_EDGE。该规则对横向截图够用,但对
 * 「竖长多页文档」会把宽度压塌(如 1621×16075 → 202×2000),正文文字糊到无法识别,导致视觉
 * 模型只能照主题幻觉。因此识图链路改用 {@link #compressTiles}:保持宽度可读、纵向切成多片,
 * 逐片送视觉模型再拼接。
 */
@Component
public class ImagePreprocessor {

    static final int MAX_EDGE = 2000;
    static final long MAX_BYTES = 3_500_000L; // 留余量,base64 膨胀后仍 < 5MB

    // 切片参数:宽度上限(超出则等比缩到此宽,不放大)、单片高度、相邻片重叠(防切断表格行)
    static final int TARGET_WIDTH = 1600;
    static final int TILE_HEIGHT = 2200;
    static final int TILE_OVERLAP = 120;

    /** 单图压缩(最长边 ≤ MAX_EDGE)。保留给非识图场景。 */
    public byte[] compress(byte[] raw) {
        return compress(raw, MAX_EDGE, MAX_BYTES);
    }

    /** 识图用:保持宽度可读,纵向切成多片(短图返回单片)。 */
    public List<byte[]> compressTiles(byte[] raw) {
        return tiles(raw, TARGET_WIDTH, TILE_HEIGHT, TILE_OVERLAP, MAX_BYTES);
    }

    static byte[] compress(byte[] raw, int maxEdge, long maxBytes) {
        BufferedImage src = decode(raw);
        BufferedImage scaled = scaleDown(src, maxEdge);
        return encodeUnderLimit(scaled, maxBytes);
    }

    /**
     * 纵向切片:先按 targetWidth 等比缩放(不放大),再以 tileHeight 高、overlap 重叠切片。
     * 切片在源图坐标系下取,避免持有一张整图大小的缩放副本。
     */
    static List<byte[]> tiles(byte[] raw, int targetWidth, int tileHeight, int overlap, long maxBytes) {
        BufferedImage src = decode(raw);
        int w = src.getWidth(), h = src.getHeight();
        double scale = w > targetWidth ? (double) targetWidth / w : 1.0;
        int outW = Math.max(1, (int) Math.round(w * scale));
        int srcTileH = Math.max(1, (int) Math.round(tileHeight / scale));
        int srcOverlap = Math.max(0, (int) Math.round(overlap / scale));
        int srcStep = Math.max(1, srcTileH - srcOverlap);

        List<byte[]> result = new ArrayList<>();
        int y = 0;
        while (y < h) {
            int sliceH = Math.min(srcTileH, h - y);
            BufferedImage slice = src.getSubimage(0, y, w, sliceH);
            int outH = Math.max(1, (int) Math.round(sliceH * scale));
            result.add(encodeUnderLimit(renderScaled(slice, outW, outH), maxBytes));
            if (y + sliceH >= h) {
                break;
            }
            y += srcStep;
        }
        return result;
    }

    private static BufferedImage decode(byte[] raw) {
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片无法解码：" + e.getMessage());
        }
        if (src == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片格式不支持或文件损坏");
        }
        return src;
    }

    private static byte[] encodeUnderLimit(BufferedImage img, long maxBytes) {
        float quality = 0.92f;
        byte[] out = writeJpeg(img, quality);
        while (out.length > maxBytes && quality > 0.3f) {
            quality -= 0.15f;
            out = writeJpeg(img, quality);
        }
        return out;
    }

    private static BufferedImage scaleDown(BufferedImage src, int maxEdge) {
        int w = src.getWidth(), h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxEdge) {
            return toRgb(src);
        }
        double ratio = (double) maxEdge / longest;
        int nw = Math.max(1, (int) Math.round(w * ratio));
        int nh = Math.max(1, (int) Math.round(h * ratio));
        return renderScaled(src, nw, nh);
    }

    /** 把(子)图绘制到指定尺寸的 RGB 画布,白底拍平透明通道。 */
    private static BufferedImage renderScaled(BufferedImage src, int nw, int nh) {
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src.getScaledInstance(nw, nh, Image.SCALE_SMOOTH), 0, 0, Color.WHITE, null);
        g.dispose();
        return dst;
    }

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(src, 0, 0, Color.WHITE, null);
        g.dispose();
        return rgb;
    }

    private static byte[] writeJpeg(BufferedImage img, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "图片压缩失败：" + e.getMessage());
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
