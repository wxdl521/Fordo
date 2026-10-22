package com.wenjin.support.rag;

import com.wenjin.support.rag.ChunkNodeLabeler.Label;
import com.wenjin.support.rag.ChunkNodeLabeler.NodeCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkNodeLabelerTest {

    private final ChunkNodeLabeler labeler = new ChunkNodeLabeler(List.of(
            new NodeCandidate("N1", "进程与线程"),
            new NodeCandidate("N2", "死锁"),
            new NodeCandidate("N3", "虚拟内存（分页）"),
            new NodeCandidate("N4", "栈"),
            new NodeCandidate("N5", "UML 类图")));

    @Test
    @DisplayName("名称完整命中：得分 10，置信度 = 得分/16")
    void fullNameHit() {
        List<Label> labels = labeler.label("死锁是指多个线程互相等待资源而无法推进。", "");
        assertThat(labels).extracting(Label::nodeCode).contains("N2");
        Label n2 = labels.stream().filter(l -> l.nodeCode().equals("N2")).findFirst().orElseThrow();
        assertThat(n2.score()).isEqualTo(10.0);
        assertThat(n2.confidence()).isEqualByComparingTo("0.625");
    }

    @Test
    @DisplayName("名称重复出现加权，最多 +2；置信度封顶 0.99")
    void repeatsAndCap() {
        Label once = labeler.label("死锁", "").get(0);
        Label many = labeler.label("死锁 死锁 死锁 死锁", "").get(0);
        assertThat(many.score()).isEqualTo(once.score() + 2);
        Label maxed = labeler.label("死锁 死锁 死锁", "死锁").get(0);
        assertThat(maxed.confidence()).isLessThanOrEqualTo(new BigDecimal("0.990"));
    }

    @Test
    @DisplayName("名称切分的关键词命中（单词 4 分）达到阈值即入选")
    void keywordHit() {
        List<Label> labels = labeler.label("每个线程拥有独立的栈空间。", "");
        assertThat(labels).extracting(Label::nodeCode).containsExactly("N1");
        assertThat(labels.get(0).score()).isEqualTo(ChunkNodeLabeler.W_KEYWORD_BODY);
        assertThat(labels.get(0).score()).isGreaterThanOrEqualTo(ChunkNodeLabeler.MIN_SCORE);
    }

    @Test
    @DisplayName("括号补充说明不参与完整名匹配；标题路径命中加权")
    void parenAndPath() {
        List<Label> body = labeler.label("虚拟内存把地址空间映射到磁盘。", "");
        assertThat(body.get(0).nodeCode()).isEqualTo("N3");
        List<Label> viaPath = labeler.label("本节讨论若干细节。", "操作系统>虚拟内存");
        assertThat(viaPath).extracting(Label::nodeCode).containsExactly("N3");
        assertThat(viaPath.get(0).score()).isEqualTo(ChunkNodeLabeler.W_NAME_PATH);
    }

    @Test
    @DisplayName("低于阈值不打标：单字节点名被忽略，通用词不当关键词")
    void belowThresholdAndShortNames() {
        assertThat(labeler.label("栈是一种后进先出的结构。", "")).isEmpty();
        assertThat(labeler.label("完全无关的内容。", "无关>标题")).isEmpty();
        ChunkNodeLabeler generic = new ChunkNodeLabeler(List.of(new NodeCandidate("G", "设计与基础")));
        assertThat(generic.label("基础设计原则", "")).isEmpty();
    }

    @Test
    @DisplayName("仅取前 2 个，按得分降序")
    void topTwoOrdered() {
        List<Label> labels = labeler.label("进程与线程相关；死锁；虚拟内存。死锁。", "");
        assertThat(labels).hasSize(2);
        // N1：全名 10 + 关键词「进程」「线程」8 = 18；N2：死锁×2 = 11；N3：虚拟内存 = 10 被截掉
        assertThat(labels).extracting(Label::nodeCode).containsExactly("N1", "N2");
        assertThat(labels.get(0).score()).isEqualTo(18.0);
        assertThat(labels.get(1).score()).isEqualTo(11.0);
    }

    @Test
    @DisplayName("ASCII 关键词大小写不敏感")
    void asciiKeyword() {
        assertThat(labeler.label("the uml diagram shows classes", "")).extracting(Label::nodeCode)
                .containsExactly("N5");
        assertThat(labeler.label("类图描述类之间的关系", "")).extracting(Label::nodeCode)
                .containsExactly("N5");
    }

    @Test
    @DisplayName("空输入/空节点安全")
    void nullSafe() {
        assertThat(labeler.label(null, null)).isEmpty();
        assertThat(new ChunkNodeLabeler(null).label("死锁", "")).isEmpty();
    }
}
