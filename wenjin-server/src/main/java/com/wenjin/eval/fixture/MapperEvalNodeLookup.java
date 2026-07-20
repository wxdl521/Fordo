package com.wenjin.eval.fixture;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgNodeMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class MapperEvalNodeLookup implements EvalNodeLookup {

    private final KgNodeMapper nodeMapper;

    public MapperEvalNodeLookup(KgNodeMapper nodeMapper) {
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
    }

    @Override
    public Optional<Long> nodeIdByCode(long courseId, String nodeCode) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return Optional.empty();
        }
        List<KgNode> list = nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .eq(KgNode::getNodeCode, nodeCode.trim())
                .isNull(KgNode::getArchivedAt)
                .last("LIMIT 1"));
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(list.get(0).getId());
    }

    @Override
    public Set<String> allNodeCodes(long courseId) {
        List<KgNode> list = nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .isNull(KgNode::getArchivedAt));
        Set<String> codes = new LinkedHashSet<>();
        if (list != null) {
            for (KgNode n : list) {
                if (n != null && n.getNodeCode() != null) {
                    codes.add(n.getNodeCode());
                }
            }
        }
        return codes;
    }
}
