package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.GraphDataVO;
import com.wenjin.service.CourseService;
import com.wenjin.service.GraphService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图谱查询接口（学生侧）。
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphService graphService;
    private final CourseService courseService;

    public GraphController(GraphService graphService, CourseService courseService) {
        this.graphService = graphService;
        this.courseService = courseService;
    }

    /**
     * 查询某课程的全部节点与边；带 studentId 时按学情染色，否则统一 unlearned。
     * GET /api/graph/{courseId}?studentId=
     */
    @GetMapping("/{courseId}")
    public Result<GraphDataVO> getGraph(@PathVariable("courseId") Long courseId,
                                        @RequestParam(value = "studentId", required = false) Long studentId) {
        courseService.assertAccessibleByStudent(studentId, courseId);
        return Result.ok(graphService.getGraph(courseId, studentId));
    }
}
