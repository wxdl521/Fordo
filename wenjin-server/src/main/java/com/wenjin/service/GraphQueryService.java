package com.wenjin.service;

import java.util.Map;
import java.util.Set;

/**
 * 图谱查询服务（只读）。供阶段二出题白名单、标注白名单、落库时 code→node_id 转换使用。
 */
public interface GraphQueryService {

    /** 目标节点 + 沿「前置」边逆向 1..depth 层前置节点的 node_code 集合（含目标自身）。 */
    Set<String> whitelistOf(Long courseId, String targetNodeCode, int depth);

    /** 课程全部 node_code（存量题标注白名单）。 */
    Set<String> allNodeCodes(Long courseId);

    /** node_code -> kg_node.id 映射（落库时把白名单 code 转 node_id 用）。 */
    Map<String, Long> codeToId(Long courseId);
}
