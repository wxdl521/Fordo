package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.NodeUpsertRequest;
import com.wenjin.dto.PendingEdgeVO;
import com.wenjin.dto.TeacherGraphVO;
import com.wenjin.service.TeacherGraphService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 教师端图谱审核控制器
 */
@RestController
@RequestMapping("/api/teacher/graph")
public class TeacherGraphController {

    private final TeacherGraphService teacherGraphService;

    public TeacherGraphController(TeacherGraphService teacherGraphService) {
        this.teacherGraphService = teacherGraphService;
    }

    /**
     * 获取完整图谱（含待复核边）
     */
    @GetMapping
    public Result<TeacherGraphVO> getGraph(@RequestParam Long courseId) {
        return Result.ok(teacherGraphService.getGraph(courseId));
    }

    /**
     * 获取待复核边列表
     */
    @GetMapping("/pending-edges")
    public Result<List<PendingEdgeVO>> pendingEdges(@RequestParam Long courseId) {
        return Result.ok(teacherGraphService.pendingEdges(courseId));
    }

    /**
     * 采纳边（去除待复核标记）
     */
    @PostMapping("/edges/{id}/accept")
    public Result<Void> acceptEdge(@PathVariable Long id) {
        teacherGraphService.acceptEdge(id);
        return Result.ok();
    }

    /**
     * 驳回边（删除）
     */
    @PostMapping("/edges/{id}/reject")
    public Result<Void> rejectEdge(@PathVariable Long id) {
        teacherGraphService.rejectEdge(id);
        return Result.ok();
    }

    /**
     * 创建节点
     */
    @PostMapping("/nodes")
    public Result<Long> createNode(@RequestParam Long courseId, @RequestBody NodeUpsertRequest req) {
        Long nodeId = teacherGraphService.createNode(courseId, req);
        return Result.ok(nodeId);
    }

    /**
     * 更新节点
     */
    @PutMapping("/nodes/{id}")
    public Result<Void> updateNode(@PathVariable Long id, @RequestBody NodeUpsertRequest req) {
        teacherGraphService.updateNode(id, req);
        return Result.ok();
    }

    /**
     * 删除节点（级联删除边和题目关联）
     */
    @DeleteMapping("/nodes/{id}")
    public Result<Void> deleteNode(@PathVariable Long id) {
        teacherGraphService.deleteNode(id);
        return Result.ok();
    }
}
