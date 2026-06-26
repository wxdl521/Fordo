package com.wenjin.support;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.wenjin.common.BusinessException;

class ImagePreprocessorTest {

    private static BufferedImage solid(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "png", b);
        return b.toByteArray();
    }

    @Test
    void tiles_tallDocument_splitsAndKeepsWidthLegible() throws Exception {
        // 竖长文档:必须纵向切片,而不是压扁成一张窄图
        byte[] in = png(solid(1600, 6000));

        List<byte[]> tiles = ImagePreprocessor.tiles(in, 1600, 2000, 100, 3_500_000L);

        assertThat(tiles.size()).isGreaterThan(1);
        for (byte[] t : tiles) {
            BufferedImage d = ImageIO.read(new ByteArrayInputStream(t));
            assertThat(d.getWidth()).isEqualTo(1600);            // 宽度保真,不塌陷
            assertThat(d.getHeight()).isLessThanOrEqualTo(2000); // 每片高度受控
            assertThat((long) t.length).isLessThanOrEqualTo(3_500_000L);
        }
    }

    @Test
    void tiles_wideOverTarget_scalesWidthToTargetNotCollapsed() throws Exception {
        // 1621 宽超长文档:旧逻辑会把宽塌到 ~200px;切片须把宽保持到目标 1600
        byte[] in = png(solid(1621, 5000));

        List<byte[]> tiles = ImagePreprocessor.tiles(in, 1600, 2200, 120, 3_500_000L);

        BufferedImage first = ImageIO.read(new ByteArrayInputStream(tiles.get(0)));
        assertThat(first.getWidth()).isEqualTo(1600);
    }

    @Test
    void tiles_shortImage_singleTileNoUpscale() throws Exception {
        byte[] in = png(solid(1200, 800));

        List<byte[]> tiles = ImagePreprocessor.tiles(in, 1600, 2200, 120, 3_500_000L);

        assertThat(tiles).hasSize(1);
        BufferedImage only = ImageIO.read(new ByteArrayInputStream(tiles.get(0)));
        assertThat(only.getWidth()).isEqualTo(1200); // 不放大
    }

    @Test
    void tiles_garbage_throws() {
        assertThatThrownBy(() -> ImagePreprocessor.tiles(new byte[]{1, 2, 3}, 1600, 2200, 120, 3_500_000L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void compress_largeImage_shrinksUnderLimits() throws Exception {
        BufferedImage big = new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = big.createGraphics();
        g.fillRect(0, 0, 4000, 3000);
        g.dispose();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(big, "png", b);

        byte[] out = ImagePreprocessor.compress(b.toByteArray(), 2000, 3_500_000L);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out));
        assertThat(decoded).isNotNull();
        assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isLessThanOrEqualTo(2000);
        assertThat((long) out.length).isLessThanOrEqualTo(3_500_000L);
    }

    @Test
    void compress_garbage_throws() {
        assertThatThrownBy(() -> ImagePreprocessor.compress(new byte[]{1, 2, 3}, 2000, 3_500_000L))
                .isInstanceOf(BusinessException.class);
    }
}
