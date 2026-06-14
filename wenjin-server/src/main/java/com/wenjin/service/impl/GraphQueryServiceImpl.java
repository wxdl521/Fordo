package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.service.GraphQueryService;
import com.wenjin.support.RelationType;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱查询服务实现。基于 kg_node/kg_edge，按课程隔离做只读查询：
 *   · whitelistOf：从目标节点沿「前置」边逆向 BFS，限深收集前置节点（含目标自身）；
 *   · allNodeCodes / codeToId：课程全部节点编码及 code→id 映射。
 */
@Service
public class GraphQueryServiceImpl implements GraphQueryService {

    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;

    public GraphQueryServiceImpl(KgNodeMapper nodeMapper, KgEdgeMapper edgeMapper) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
    }

    @Override
    public Set<String> whitelistOf(Long courseId, String targetNodeCode, int depth) {
        List<KgNode> nodes = listNodes(courseId);

        // 建 code↔id 双向映射
        Map<String, Long> codeToId = new HashMap<>();
        Map<Long, String> idToCode = new HashMap<>();
        for (KgNode n : nodes) {
            codeToId.put(n.getNodeCode(), n.getId());
            idToCode.put(n.getId(), n.getNodeCode());
        }

        Set<String> result = new LinkedHashSet<>();
        Long targetId = codeToId.get(targetNodeCode);
        if (targetId == null) {
            return result; // 目标不在该课程，返回空集
        }
        result.add(targetNodeCode); // 含目标自身

        if (depth <= 0) {
            return result;
        }

        // 逆邻接：toNodeId -> [fromNodeId...]，仅取「前置」边
        Map<Long, List<Long>> reverse = new HashMap<>();
        for (KgEdge e : edgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>().eq(KgEdge::getCourseId, courseId))) {
            if (e.getRelationType() == null
                    || e.getRelationType() != RelationType.PREREQUISITE.getCode()) {
                continue;
            }
            reverse.computeIfAbsent(e.getToNodeId(), k -> new ArrayList<>()).add(e.getFromNodeId());
        }

        // 从目标出发逐层逆向 BFS，最多 depth 层
        Set<Long> visited = new LinkedHashSet<>();
        visited.add(targetId);
        Deque<Long> frontier = new ArrayDeque<>();
        frontier.add(targetId);
        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            int size = frontier.size();
            for (int i = 0; i < size; i++) {
                Long current = frontier.poll();
                for (Long prereqId : reverse.getOrDefault(current, List.of())) {
                    if (visited.add(prereqId)) {
                        frontier.add(prereqId);
                        String code = idToCode.get(prereqId);
                        if (code != null) {
                            result.add(code);
                        }
                    }
                }
            }
        }

        return result;
    }

    @Override
    public Set<String> allNodeCodes(Long courseId) {
        Set<String> codes = new LinkedHashSet<>();
        for (KgNode n : listNodes(courseId)) {
            codes.add(n.getNodeCode());
        }
        return codes;
    }

    @Override
    public Map<String, Long> codeToId(Long courseId) {
        Map<String, Long> map = new HashMap<>();
        for (KgNode n : listNodes(courseId)) {
            map.put(n.getNodeCode(), n.getId());
        }
        return map;
    }

    private List<KgNode> listNodes(Long courseId) {
        return nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));
    }
}
