package com.wenjin.support;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.LimitsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
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
 * 「竖长多页文档」会把宽度压塌,因此识图链路改用 {@link #compressTiles}:保持宽度可读、纵向切成多片。
 * <p>解码前用 {@link ImageReader#getWidth}/{@link ImageReader#getHeight} 做像素与切片预算校验。
 */
@Component
public class ImagePreprocessor {

    static final int MAX_EDGE = 2000;
    static final long MAX_BYTES = 3_500_000L;

    static final int TARGET_WIDTH = 1600;
    static final int TILE_HEIGHT = 2200;
    static final int TILE_OVERLAP = 120;

    static final int DEFAULT_MAX_DIMENSION = 10_000;
    static final long DEFAULT_MAX_PIXELS = 40_000_000L;
    static final int DEFAULT_MAX_PREDICTED_TILES = 12;

    private final LimitsProperties limits;

    @Autowired
    public ImagePreprocessor(LimitsProperties limits) {
        this.limits = limits == null ? new LimitsProperties() : limits;
    }

    /** 测试/无 Spring 时使用默认限额。 */
    public ImagePreprocessor() {
        this(new LimitsProperties());
    }

    /** 单图压缩(最长边 ≤ MAX_EDGE)。保留给非识图场景。 */
    public byte[] compress(byte[] raw) {
        return compress(raw, MAX_EDGE, MAX_BYTES);
    }

    /** 识图用:保持宽度可读,纵向切成多片(短图返回单片)。 */
    public List<byte[]> compressTiles(byte[] raw) {
        LimitsProperties.Image img = limits.getImage();
        return tiles(raw,
                img.getTargetWidth() > 0 ? img.getTargetWidth() : TARGET_WIDTH,
                img.getTileHeight() > 0 ? img.getTileHeight() : TILE_HEIGHT,
                img.getTileOverlap() >= 0 ? img.getTileOverlap() : TILE_OVERLAP,
                MAX_BYTES,
                img.getMaxDimension() > 0 ? img.getMaxDimension() : DEFAULT_MAX_DIMENSION,
                img.getMaxPixels() > 0 ? img.getMaxPixels() : DEFAULT_MAX_PIXELS,
                img.getMaxPredictedTiles() > 0 ? img.getMaxPredictedTiles() : DEFAULT_MAX_PREDICTED_TILES);
    }

    static byte[] compress(byte[] raw, int maxEdge, long maxBytes) {
        BufferedImage src = decodeWithBounds(raw, DEFAULT_MAX_DIMENSION, DEFAULT_MAX_PIXELS,
                DEFAULT_MAX_PREDICTED_TILES, TARGET_WIDTH, TILE_HEIGHT, TILE_OVERLAP, false);
        BufferedImage scaled = scaleDown(src, maxEdge);
        return encodeUnderLimit(scaled, maxBytes);
    }

    /**
     * 纵向切片:先按 targetWidth 等比缩放(不放大),再以 tileHeight 高、overlap 重叠切片。
     */
    static List<byte[]> tiles(byte[] raw, int targetWidth, int tileHeight, int overlap, long maxBytes) {
        return tiles(raw, targetWidth, tileHeight, overlap, maxBytes,
                DEFAULT_MAX_DIMENSION, DEFAULT_MAX_PIXELS, DEFAULT_MAX_PREDICTED_TILES);
    }

    static List<byte[]> tiles(byte[] raw,
                              int targetWidth,
                              int tileHeight,
                              int overlap,
                              long maxBytes,
                              int maxDimension,
                              long maxPixels,
                              int maxPredictedTiles) {
        BufferedImage src = decodeWithBounds(raw, maxDimension, maxPixels, maxPredictedTiles,
                targetWidth, tileHeight, overlap, true);
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

    /**
     * 预测纵向切片数（与 {@link #tiles} 步进一致）。
     */
    static int predictTileCount(int width, int height, int targetWidth, int tileHeight, int overlap) {
        if (height <= 0 || width <= 0) {
            return 0;
        }
        double scale = width > targetWidth ? (double) targetWidth / width : 1.0;
        int srcTileH = Math.max(1, (int) Math.round(tileHeight / scale));
        int srcOverlap = Math.max(0, (int) Math.round(overlap / scale));
        int srcStep = Math.max(1, srcTileH - srcOverlap);
        int count = 0;
        int y = 0;
        while (y < height) {
            count++;
            int sliceH = Math.min(srcTileH, height - y);
            if (y + sliceH >= height) {
                break;
            }
            y += srcStep;
            if (count > 1_000_000) { // 防异常参数死循环
                break;
            }
        }
        return count;
    }

    /**
     * 先读宽高做预算校验，通过后再 full decode。
     */
    private static BufferedImage decodeWithBounds(byte[] raw,
                                                  int maxDimension,
                                                  long maxPixels,
                                                  int maxPredictedTiles,
                                                  int targetWidth,
                                                  int tileHeight,
                                                  int overlap,
                                                  boolean checkTiles) {
        if (raw == null || raw.length == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片为空");
        }
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            if (iis == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "图片格式不支持或文件损坏");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "图片格式不支持或文件损坏");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                assertImageBounds(w, h, maxDimension, maxPixels);
                if (checkTiles) {
                    int predicted = predictTileCount(w, h, targetWidth, tileHeight, overlap);
                    if (predicted > maxPredictedTiles) {
                        throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE,
                                "图片过高，预计切片数 " + predicted + " 超过上限 " + maxPredictedTiles
                                        + "，请裁剪后重试");
                    }
                }
                BufferedImage src = reader.read(0);
                if (src == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "图片格式不支持或文件损坏");
                }
                return src;
            } finally {
                reader.dispose();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片无法解码：" + e.getMessage());
        }
    }

    static void assertImageBounds(int w, int h, int maxDimension, long maxPixels) {
        if (w <= 0 || h <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片尺寸无效");
        }
        if (w > maxDimension || h > maxDimension) {
            throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE,
                    "图片边长超限：" + w + "x" + h + "（单边上限 " + maxDimension + "）");
        }
        long pixels = (long) w * (long) h;
        if (pixels > maxPixels) {
            throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE,
                    "图片像素超限：" + pixels + " > " + maxPixels);
        }
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
