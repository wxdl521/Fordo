package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.AccessGuard;
import com.wenjin.dto.StudentChunkVO;
import com.wenjin.service.rag.StudentRagService;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端 RAG 切块读取（引用抽屉）。
 * <p>{@code rag-enabled=false} 或未选课 → 404。
 */
@RestController
@RequestMapping("/api/student/rag")
@Validated
public class StudentRagController {

    private final StudentRagService studentRagService;

    public StudentRagController(StudentRagService studentRagService) {
        this.studentRagService = studentRagService;
    }

    /** GET /api/student/rag/chunks/{id} */
    @GetMapping("/chunks/{id}")
    public Result<StudentChunkVO> getChunk(@PathVariable("id") @Positive Long id) {
        long studentId = AccessGuard.requireSelfStudent(null);
        return Result.ok(studentRagService.getChunkForStudent(studentId, id));
    }
}
