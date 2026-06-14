package com.wenjin.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RelationType 枚举：中文标签 <-> TINYINT 编码互转的特征测试。
 */
class RelationTypeTest {

    @Test
    @DisplayName("fromLabel 能识别四种合法中文标签")
    void fromLabel_recognizesAllLabels() {
        assertThat(RelationType.fromLabel("前置")).isEqualTo(RelationType.PREREQUISITE);
        assertThat(RelationType.fromLabel("包含")).isEqualTo(RelationType.CONTAINS);
        assertThat(RelationType.fromLabel("相关")).isEqualTo(RelationType.RELATED);
        assertThat(RelationType.fromLabel("应用")).isEqualTo(RelationType.APPLIES);
    }

    @Test
    @DisplayName("fromLabel 会去除首尾空白")
    void fromLabel_trimsWhitespace() {
        assertThat(RelationType.fromLabel("  前置 ")).isEqualTo(RelationType.PREREQUISITE);
    }

    @Test
    @DisplayName("fromLabel 对未知标签或 null 返回 null")
    void fromLabel_unknownReturnsNull() {
        assertThat(RelationType.fromLabel("依赖")).isNull();
        assertThat(RelationType.fromLabel("")).isNull();
        assertThat(RelationType.fromLabel(null)).isNull();
    }

    @Test
    @DisplayName("labelOf 能把编码翻回中文标签")
    void labelOf_mapsCodeToLabel() {
        assertThat(RelationType.labelOf(1)).isEqualTo("前置");
        assertThat(RelationType.labelOf(2)).isEqualTo("包含");
        assertThat(RelationType.labelOf(3)).isEqualTo("相关");
        assertThat(RelationType.labelOf(4)).isEqualTo("应用");
    }

    @Test
    @DisplayName("labelOf 对未知编码回退为编码字符串，对 null 返回 null")
    void labelOf_unknownFallsBack() {
        assertThat(RelationType.labelOf(9)).isEqualTo("9");
        assertThat(RelationType.labelOf(null)).isNull();
    }

    @Test
    @DisplayName("每个枚举的 code 与 label 自洽")
    void enumSelfConsistent() {
        for (RelationType t : RelationType.values()) {
            assertThat(RelationType.fromLabel(t.getLabel())).isEqualTo(t);
            assertThat(RelationType.labelOf(t.getCode())).isEqualTo(t.getLabel());
        }
    }
}
