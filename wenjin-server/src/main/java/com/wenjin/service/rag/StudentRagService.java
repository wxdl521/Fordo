package com.wenjin.service.rag;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.StudentChunkVO;
import com.wenjin.entity.DocChunk;
import com.wenjin.entity.DocSource;
import com.wenjin.mapper.DocChunkMapper;
import com.wenjin.mapper.DocSourceMapper;
import com.wenjin.service.CourseService;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 学生端 RAG 切块读取：仅已选课课程下的 chunk 可访问；开关关 → 404。
 */
@Service
public class StudentRagService {

    private final DocChunkMapper chunkMapper;
    private final DocSourceMapper sourceMapper;
    private final CourseService courseService;
    private final FeatureFlagsProperties featureFlags;

    public StudentRagService(DocChunkMapper chunkMapper,
                             DocSourceMapper sourceMapper,
                             CourseService courseService,
                             FeatureFlagsProperties featureFlags) {
        this.chunkMapper = Objects.requireNonNull(chunkMapper);
        this.sourceMapper = Objects.requireNonNull(sourceMapper);
        this.courseService = Objects.requireNonNull(courseService);
        this.featureFlags = featureFlags;
    }

    /**
     * 返回切块正文 + 来源标题；未选课 / 不存在 / RAG 关闭 → 404。
     */
    public StudentChunkVO getChunkForStudent(long studentId, long chunkId) {
        assertRagEnabled();
        if (chunkId <= 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        DocChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || chunk.getSourceId() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        DocSource source = sourceMapper.selectById(chunk.getSourceId());
        if (source == null || source.getCourseId() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        try {
            courseService.assertAccessibleByStudent(studentId, source.getCourseId());
        } catch (BusinessException e) {
            // 统一 404，避免泄露课程/选课存在性
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }

        StudentChunkVO vo = new StudentChunkVO();
        vo.setId(chunk.getId());
        vo.setContent(chunk.getContent());
        vo.setSourceTitle(source.getTitle());
        vo.setMetaJson(chunk.getMetaJson());
        return vo;
    }

    private void assertRagEnabled() {
        if (featureFlags == null || !featureFlags.isRagEnabled()) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
    }
}
