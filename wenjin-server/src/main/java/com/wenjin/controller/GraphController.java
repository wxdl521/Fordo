package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.GraphDataVO;
import com.wenjin.service.GraphService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图谱查询接口（学生侧）。
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphService graphService;

    public GraphController(GraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * 查询某课程的全部节点与边（节点掌握度本阶段统一 unlearned）。
     * GET /api/graph/{courseId}
     */
    @GetMapping("/{courseId}")
    public Result<GraphDataVO> getGraph(@PathVariable("courseId") Long courseId) {
        return Result.ok(graphService.getGraph(courseId));
    }
}
