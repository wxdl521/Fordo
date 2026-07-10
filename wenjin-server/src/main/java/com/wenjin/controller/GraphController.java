package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.GraphDataVO;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.service.CourseService;
import com.wenjin.service.GraphService;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图谱查询接口（学生侧）。
 */
@RestController
@RequestMapping("/api/graph")
@Validated
public class GraphController {

    private final GraphService graphService;
    private final CourseService courseService;

    public GraphController(GraphService graphService, CourseService courseService) {
        this.graphService = graphService;
        this.courseService = courseService;
    }

    /**
     * 查询某课程的全部节点与边。
     * <ul>
     *   <li>匿名：已发布课程的公共图谱（统一 unlearned 染色）</li>
     *   <li>登录学生：掌握度染色由 principal 自动附加（不可由请求者指定他人 studentId）</li>
     * </ul>
     * GET /api/graph/{courseId}
     */
    @GetMapping("/{courseId}")
    public Result<GraphDataVO> getGraph(@PathVariable("courseId") @Positive Long courseId) {
        Long masteryStudentId = null;
        CurrentPrincipal principal = CurrentUser.getPrincipal();
        if (principal != null && principal.isStudent()) {
            masteryStudentId = principal.userId();
        }
        courseService.assertAccessibleByStudent(masteryStudentId, courseId);
        return Result.ok(graphService.getGraph(courseId, masteryStudentId));
    }
}
