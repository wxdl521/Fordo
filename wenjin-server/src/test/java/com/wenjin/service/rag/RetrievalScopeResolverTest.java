package com.wenjin.service.rag;

import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.support.PrereqDirection;
import com.wenjin.support.ReviewMarker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetrievalScopeResolverTest {

    @Mock private KgNodeMapper nodeMapper;
    @Mock private KgEdgeMapper edgeMapper;

    private LimitsProperties limits;
    private RetrievalScopeResolver resolver;

    @BeforeEach
    void setUp() {
        limits = new LimitsProperties();
        resolver = new RetrievalScopeResolver(nodeMapper, edgeMapper, limits);
        when(nodeMapper.selectOne(any())).thenReturn(node(10L, "KT05"));
    }

    private static KgNode node(long id, String code) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setNodeCode(code);
        return n;
    }

    private static KgEdge prereq(long from, long to, String note) {
        KgEdge e = new KgEdge();
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setRelationType(PrereqDirection.RELATION_TYPE_PREREQUISITE);
        e.setRelationNote(note);
        return e;
    }

    @Test
    @DisplayName("焦点 + 直接前置（from→to=焦点），『待复核』边跳过，前置按 code 升序")
    void focusPlusDirectPrereqs() {
        when(edgeMapper.selectList(any())).thenReturn(List.of(
                prereq(4L, 10L, null),
                prereq(3L, 10L, null),
                prereq(9L, 10L, ReviewMarker.PREFIX + "x")));
        when(nodeMapper.selectList(any())).thenReturn(List.of(node(4L, "KT04"), node(3L, "KT03")));

        assertThat(resolver.scopeFor(5L, "KT05")).containsExactly("KT05", "KT03", "KT04");
    }

    @Test
    @DisplayName("expand-prerequisites=false → 只焦点，不查图")
    void disabled() {
        limits.getRag().setExpandPrerequisites(false);
        assertThat(resolver.scopeFor(5L, "KT05")).containsExactly("KT05");
        verify(edgeMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("无前置 / 焦点不存在 / 焦点为空")
    void edgeCases() {
        when(edgeMapper.selectList(any())).thenReturn(List.of());
        assertThat(resolver.scopeFor(5L, "KT05")).containsExactly("KT05");
        when(nodeMapper.selectOne(any())).thenReturn(null);
        assertThat(resolver.scopeFor(5L, "KT99")).containsExactly("KT99");
        assertThat(resolver.scopeFor(5L, " ")).isEmpty();
    }
}
