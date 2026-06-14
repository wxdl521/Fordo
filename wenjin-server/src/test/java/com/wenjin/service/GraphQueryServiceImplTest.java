package com.wenjin.service;

import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.service.impl.GraphQueryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * GraphQueryServiceImpl 单元测试：用 Mockito 隔离数据库依赖，
 * 覆盖沿「前置」边逆向 BFS 求白名单（含目标自身、限深、不串「包含」边）。
 *
 * 图：KT01→KT04→KT05→KT07 均为「前置」边（relationType=1）；
 *     另有 KT07→KT07-2「包含」边（relationType=2，不应进白名单）。
 * 主键：KT01=101, KT04=104, KT05=105, KT07=107, KT07-2=172。
 */
@ExtendWith(MockitoExtension.class)
class GraphQueryServiceImplTest {

    @Mock KgNodeMapper nodeMapper;
    @Mock KgEdgeMapper edgeMapper;

    private GraphQueryServiceImpl service() {
        return new GraphQueryServiceImpl(nodeMapper, edgeMapper);
    }

    private List<KgNode> nodes() {
        return List.of(
                kgNode(101L, "KT01"),
                kgNode(104L, "KT04"),
                kgNode(105L, "KT05"),
                kgNode(107L, "KT07"),
                kgNode(172L, "KT07-2"));
    }

    private List<KgEdge> edges() {
        return List.of(
                kgEdge(101L, 104L, 1), // KT01 -> KT04 前置
                kgEdge(104L, 105L, 1), // KT04 -> KT05 前置
                kgEdge(105L, 107L, 1), // KT05 -> KT07 前置
                kgEdge(107L, 172L, 2)); // KT07 -> KT07-2 包含（不进白名单）
    }

    private void stubGraph() {
        when(nodeMapper.selectList(any())).thenReturn(nodes());
        when(edgeMapper.selectList(any())).thenReturn(edges());
    }

    @Test
    @DisplayName("深度2：目标 + 1层前置 + 2层前置，不含3层、不含包含邻居")
    void whitelistDepth2() {
        stubGraph();
        Set<String> wl = service().whitelistOf(1L, "KT07", 2);
        assertThat(wl).containsExactlyInAnyOrder("KT07", "KT05", "KT04");
        assertThat(wl).doesNotContain("KT01");   // 3层前置，超出深度
        assertThat(wl).doesNotContain("KT07-2"); // 「包含」邻居不进
    }

    @Test
    @DisplayName("深度1：仅目标 + 1层前置")
    void whitelistDepth1() {
        stubGraph();
        Set<String> wl = service().whitelistOf(1L, "KT07", 1);
        assertThat(wl).containsExactlyInAnyOrder("KT07", "KT05");
    }

    @Test
    @DisplayName("目标无前置：仅返回目标自身")
    void whitelistNoPrereq() {
        stubGraph();
        Set<String> wl = service().whitelistOf(1L, "KT01", 2);
        assertThat(wl).containsExactly("KT01");
    }

    @Test
    @DisplayName("allNodeCodes 返回课程全部节点编码")
    void allNodeCodes() {
        when(nodeMapper.selectList(any())).thenReturn(nodes());
        Set<String> all = service().allNodeCodes(1L);
        assertThat(all).containsExactlyInAnyOrder("KT01", "KT04", "KT05", "KT07", "KT07-2");
    }

    @Test
    @DisplayName("codeToId 返回 node_code -> kg_node.id 映射")
    void codeToId() {
        when(nodeMapper.selectList(any())).thenReturn(nodes());
        Map<String, Long> map = service().codeToId(1L);
        assertThat(map).containsEntry("KT01", 101L)
                .containsEntry("KT04", 104L)
                .containsEntry("KT05", 105L)
                .containsEntry("KT07", 107L)
                .containsEntry("KT07-2", 172L);
    }

    private KgNode kgNode(Long id, String code) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setNodeCode(code);
        n.setCourseId(1L);
        return n;
    }

    private KgEdge kgEdge(Long from, Long to, int type) {
        KgEdge e = new KgEdge();
        e.setCourseId(1L);
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setRelationType(type);
        return e;
    }
}
