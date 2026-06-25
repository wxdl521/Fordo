package com.wenjin.support;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.wenjin.common.BusinessException;

class ImagePreprocessorTest {

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
