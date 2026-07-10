package com.wenjin.service.companion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgNodeMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class NodeToolSupport {

    private final KgNodeMapper nodeMapper;

    public NodeToolSupport(KgNodeMapper nodeMapper) {
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
    }

    public KgNode requireNode(Long courseId, String nodeCode) throws ToolException {
        if (!StringUtils.hasText(nodeCode)) {
            throw new ToolException("nodeCode is required");
        }
        KgNode node = nodeMapper.selectOne(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .eq(KgNode::getNodeCode, nodeCode.trim())
                .isNull(KgNode::getArchivedAt));
        if (node == null) {
            throw new ToolException("nodeCode not found in course: " + nodeCode.trim());
        }
        return node;
    }

    public KgNode requireNode(Long courseId, String nodeCode, String nodeName) throws ToolException {
        if (StringUtils.hasText(nodeCode)) {
            return requireNode(courseId, nodeCode);
        }
        if (!StringUtils.hasText(nodeName)) {
            throw new ToolException("nodeCode or nodeName is required");
        }
        String normalizedName = nodeName.trim();
        List<KgNode> matches = nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .eq(KgNode::getName, normalizedName)
                .isNull(KgNode::getArchivedAt));
        if (matches == null || matches.isEmpty()) {
            throw new ToolException("nodeName not found in course: " + normalizedName);
        }
        if (matches.size() > 1) {
            throw new ToolException("ambiguous nodeName in course: " + normalizedName);
        }
        return matches.get(0);
    }

    public Map<String, Object> nodeRef(KgNode node) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("nodeCode", node.getNodeCode());
        ref.put("name", node.getName());
        return ref;
    }
}
