package com.wenjin.service;

import com.wenjin.dto.NodeUpsertRequest;
import com.wenjin.dto.PendingEdgeVO;
import com.wenjin.dto.TeacherGraphVO;

import java.util.List;

/**
 * 教师端图谱审核服务
 */
public interface TeacherGraphService {

    /**
     * 获取完整图谱（含待复核边）
     */
    TeacherGraphVO getGraph(Long courseId);

    /**
     * 获取待复核边列表（按置信度降序）
     */
    List<PendingEdgeVO> pendingEdges(Long courseId);

    /**
     * 采纳边（去除待复核标记）
     */
    void acceptEdge(Long edgeId);

    /**
     * 驳回边（删除）
     */
    void rejectEdge(Long edgeId);

    /**
     * 创建节点
     * @return 新节点 ID
     */
    Long createNode(Long courseId, NodeUpsertRequest req);

    /**
     * 更新节点
     */
    void updateNode(Long nodeId, NodeUpsertRequest req);

    /**
     * 删除节点（级联删除边和题目关联）
     */
    void deleteNode(Long nodeId);
}
