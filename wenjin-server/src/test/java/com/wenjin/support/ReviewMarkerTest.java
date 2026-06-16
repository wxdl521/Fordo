package com.wenjin.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewMarkerTest {

    @Test
    void detectsPendingPrefix() {
        assertThat(ReviewMarker.isPending("『待复核』原因说明")).isTrue();
        assertThat(ReviewMarker.isPending("『待复核』")).isTrue();
        assertThat(ReviewMarker.isPending("正常关系描述")).isFalse();
        assertThat(ReviewMarker.isPending(null)).isFalse();
        assertThat(ReviewMarker.isPending("")).isFalse();
    }

    @Test
    void stripsPrefixAndColon() {
        assertThat(ReviewMarker.strip("『待复核』原因说明")).isEqualTo("原因说明");
        assertThat(ReviewMarker.strip("『待复核』")).isEqualTo("");
        assertThat(ReviewMarker.strip("正常文本")).isEqualTo("正常文本");
        assertThat(ReviewMarker.strip(null)).isNull();
        assertThat(ReviewMarker.strip("")).isEqualTo("");
    }
}
