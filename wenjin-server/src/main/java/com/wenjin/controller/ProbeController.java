package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.AccessGuard;
import com.wenjin.dto.ProbeStartVO;
import com.wenjin.dto.ProbeSubmitRequest;
import com.wenjin.dto.ProbeSubmitVO;
import com.wenjin.service.CourseService;
import com.wenjin.service.companion.ProbeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Probe understanding endpoints (M9). Submit never updates mastery (EWMA isolation).
 */
@RestController
@RequestMapping("/api/companion/probe")
@Validated
public class ProbeController {

    private final ProbeService probeService;
    private final CourseService courseService;

    public ProbeController(ProbeService probeService, CourseService courseService) {
        this.probeService = probeService;
        this.courseService = courseService;
    }

    /**
     * Start a probe (tool-path preferred; REST for clients that need a direct start).
     * POST /api/companion/probe/start?courseId=&nodeCode=
     */
    @PostMapping("/start")
    public Result<ProbeStartVO> start(@RequestParam @NotNull @Positive Long courseId,
                                      @RequestParam @NotBlank String nodeCode) {
        long studentId = AccessGuard.requireSelfStudent(null);
        courseService.assertAccessibleByStudent(studentId, courseId);
        return Result.ok(probeService.startProbe(studentId, courseId, nodeCode));
    }

    /**
     * Submit probe answer → correct/incorrect + reexplainAvailable.
     * POST /api/companion/probe/{sessionId}/submit
     */
    @PostMapping("/{sessionId}/submit")
    public Result<ProbeSubmitVO> submit(@PathVariable @NotBlank String sessionId,
                                        @Valid @RequestBody ProbeSubmitRequest req) {
        long studentId = AccessGuard.requireSelfStudent(req.getStudentId());
        req.setStudentId(studentId);
        courseService.assertAccessibleByStudent(studentId, req.getCourseId());
        return Result.ok(probeService.submit(sessionId, req));
    }
}
