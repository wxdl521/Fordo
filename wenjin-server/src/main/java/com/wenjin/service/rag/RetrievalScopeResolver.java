package com.wenjin.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.config.LimitsProperties;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.support.PrereqDirection;
import com.wenjin.support.ReviewMarker;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 伴侣 RETRIEVAL 注入的节点范围（R2）：焦点节点 + 其直接前置（{@code wenjin.limits.rag.expand-prerequisites}）。
 * <p>前置边语义同 {@link PrereqDirection}（from 是 to 的前置），只取未归档、非『待复核』的前置边与未归档节点；
 * 焦点节点在首位，前置按 nodeCode 升序（确定性）。关闭或焦点为空时返回 [焦点] / []。
 */
@Component
public class RetrievalScopeResolver {

    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;
    private final LimitsProperties limits;

    public RetrievalScopeResolver(KgNodeMapper nodeMapper, KgEdgeMapper edgeMapper, LimitsProperties limits) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.limits = limits;
    }

    public List<String> scopeFor(Long courseId, String focusNodeCode) {
        if (!StringUtils.hasText(focusNodeCode)) {
            return List.of();
        }
        String focus = focusNodeCode.trim();
        if (courseId == null || limits == null || limits.getRag() == null || !limits.getRag().isExpandPrerequisites()) {
            return List.of(focus);
        }
        KgNode node = nodeMapper.selectOne(new QueryWrapper<KgNode>()
                .eq("course_id", courseId)
                .eq("node_code", focus)
                .isNull("archived_at")
                .last("LIMIT 1"));
        if (node == null || node.getId() == null) {
            return List.of(focus);
        }
        List<KgEdge> edges = edgeMapper.selectList(new QueryWrapper<KgEdge>()
                .eq("course_id", courseId)
                .eq("to_node_id", node.getId())
                .eq("relation_type", PrereqDirection.RELATION_TYPE_PREREQUISITE)
                .isNull("archived_at"));
        List<Long> prereqIds = new ArrayList<>();
        if (edges != null) {
            for (KgEdge e : edges) {
                if (e == null || !Objects.equals(e.getRelationType(), PrereqDirection.RELATION_TYPE_PREREQUISITE)
                        || !Objects.equals(PrereqDirection.dependentNodeId(e), node.getId())
                        || ReviewMarker.isPending(e.getRelationNote())) {
                    continue;
                }
                Long pre = PrereqDirection.prerequisiteNodeId(e);
                if (pre != null && !pre.equals(node.getId())) {
                    prereqIds.add(pre);
                }
            }
        }
        if (prereqIds.isEmpty()) {
            return List.of(focus);
        }
        List<KgNode> prereqs = nodeMapper.selectList(new QueryWrapper<KgNode>()
                .eq("course_id", courseId)
                .in("id", prereqIds)
                .isNull("archived_at"));
        Set<String> codes = new TreeSet<>();
        if (prereqs != null) {
            for (KgNode p : prereqs) {
                if (p != null && StringUtils.hasText(p.getNodeCode()) && !focus.equals(p.getNodeCode())) {
                    codes.add(p.getNodeCode());
                }
            }
        }
        List<String> out = new ArrayList<>(codes.size() + 1);
        out.add(focus);
        out.addAll(codes);
        return List.copyOf(out);
    }
}
