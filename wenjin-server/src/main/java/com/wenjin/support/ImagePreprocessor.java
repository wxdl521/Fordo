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
import java.util.Iterator;

/** 图片预处理:降采样到最长边 ≤ MAX_EDGE,并迭代降低 JPEG 质量使结果 ≤ MAX_BYTES。 */
@Component
public class ImagePreprocessor {

    static final int MAX_EDGE = 2000;
    static final long MAX_BYTES = 3_500_000L; // 留余量,base64 膨胀后仍 < 5MB

    public byte[] compress(byte[] raw) {
        return compress(raw, MAX_EDGE, MAX_BYTES);
    }

    static byte[] compress(byte[] raw, int maxEdge, long maxBytes) {
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片无法解码：" + e.getMessage());
        }
        if (src == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片格式不支持或文件损坏");
        }
        BufferedImage scaled = scaleDown(src, maxEdge);
        float quality = 0.92f;
        byte[] out = writeJpeg(scaled, quality);
        while (out.length > maxBytes && quality > 0.3f) {
            quality -= 0.15f;
            out = writeJpeg(scaled, quality);
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
