package com.wenjin.controller;

import com.wenjin.audit.Audited;
import com.wenjin.common.Result;
import com.wenjin.dto.EdgeCreateRequest;
import com.wenjin.dto.NodeUpsertRequest;
import com.wenjin.dto.PendingEdgeVO;
import com.wenjin.dto.TeacherGraphVO;
import com.wenjin.service.TeacherGraphService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 教师端图谱审核控制器
 */
@RestController
@RequestMapping("/api/teacher/graph")
@Validated
public class TeacherGraphController {

    private final TeacherGraphService teacherGraphService;

    public TeacherGraphController(TeacherGraphService teacherGraphService) {
        this.teacherGraphService = teacherGraphService;
    }

    /**
     * 获取完整图谱（含待复核边）
     */
    @GetMapping
    public Result<TeacherGraphVO> getGraph(@RequestParam @Positive Long courseId) {
        return Result.ok(teacherGraphService.getGraph(courseId));
    }

    /**
     * 获取待复核边列表
     */
    @GetMapping("/pending-edges")
    public Result<List<PendingEdgeVO>> pendingEdges(@RequestParam @Positive Long courseId) {
        return Result.ok(teacherGraphService.pendingEdges(courseId));
    }

    /**
     * 创建边（归属 + 自环/重复/前置环路校验）
     */
    @Audited(action = "GRAPH_EDGE_CREATE", target = "GRAPH")
    @PostMapping("/edges")
    public Result<Long> createEdge(@RequestParam @Positive Long courseId,
                                   @Valid @RequestBody EdgeCreateRequest req) {
        Long edgeId = teacherGraphService.createEdge(courseId, req);
        return Result.ok(edgeId);
    }

    /**
     * 采纳边（去除待复核标记）
     */
    @Audited(action = "GRAPH_EDGE_ACCEPT", target = "GRAPH")
    @PostMapping("/edges/{id}/accept")
    public Result<Void> acceptEdge(@PathVariable @Positive Long id) {
        teacherGraphService.acceptEdge(id);
        return Result.ok();
    }

    /**
     * 驳回边（删除）
     */
    @Audited(action = "GRAPH_EDGE_REJECT", target = "GRAPH")
    @PostMapping("/edges/{id}/reject")
    public Result<Void> rejectEdge(@PathVariable @Positive Long id) {
        teacherGraphService.rejectEdge(id);
        return Result.ok();
    }

    /**
     * 创建节点
     */
    @Audited(action = "GRAPH_NODE_CREATE", target = "GRAPH")
    @PostMapping("/nodes")
    public Result<Long> createNode(@RequestParam @Positive Long courseId,
                                   @Valid @RequestBody NodeUpsertRequest req) {
        Long nodeId = teacherGraphService.createNode(courseId, req);
        return Result.ok(nodeId);
    }

    /**
     * 更新节点
     */
    @Audited(action = "GRAPH_NODE_UPDATE", target = "GRAPH")
    @PutMapping("/nodes/{id}")
    public Result<Void> updateNode(@PathVariable @Positive Long id,
                                   @Valid @RequestBody NodeUpsertRequest req) {
        teacherGraphService.updateNode(id, req);
        return Result.ok();
    }

    /**
     * 删除节点（级联删除边和题目关联）
     */
    @Audited(action = "GRAPH_NODE_DELETE", target = "GRAPH")
    @DeleteMapping("/nodes/{id}")
    public Result<Void> deleteNode(@PathVariable @Positive Long id) {
        teacherGraphService.deleteNode(id);
        return Result.ok();
    }
}
