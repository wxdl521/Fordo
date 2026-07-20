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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentRagServiceTest {

    @Mock private DocChunkMapper chunkMapper;
    @Mock private DocSourceMapper sourceMapper;
    @Mock private CourseService courseService;

    private FeatureFlagsProperties featureFlags;
    private StudentRagService service;

    @BeforeEach
    void setUp() {
        featureFlags = new FeatureFlagsProperties();
        featureFlags.setRagEnabled(true);
        service = new StudentRagService(chunkMapper, sourceMapper, courseService, featureFlags);
    }

    @Test
    @DisplayName("已选课学生可读 chunk 正文与来源标题")
    void enrolledStudent_canReadChunk() {
        DocChunk chunk = new DocChunk();
        chunk.setId(101L);
        chunk.setSourceId(5L);
        chunk.setContent("用例图强调参与者与边界");
        chunk.setMetaJson("{\"path\":\"SE\"}");
        DocSource source = new DocSource();
        source.setId(5L);
        source.setCourseId(10L);
        source.setTitle("软件工程参考");
        when(chunkMapper.selectById(101L)).thenReturn(chunk);
        when(sourceMapper.selectById(5L)).thenReturn(source);
        doNothing().when(courseService).assertAccessibleByStudent(1L, 10L);

        StudentChunkVO vo = service.getChunkForStudent(1L, 101L);

        assertThat(vo.getId()).isEqualTo(101L);
        assertThat(vo.getContent()).contains("用例图");
        assertThat(vo.getSourceTitle()).isEqualTo("软件工程参考");
        assertThat(vo.getMetaJson()).contains("path");
    }

    @Test
    @DisplayName("未选课 → 404（不泄露选课状态）")
    void notEnrolled_notFound() {
        DocChunk chunk = new DocChunk();
        chunk.setId(101L);
        chunk.setSourceId(5L);
        chunk.setContent("secret");
        DocSource source = new DocSource();
        source.setId(5L);
        source.setCourseId(10L);
        when(chunkMapper.selectById(101L)).thenReturn(chunk);
        when(sourceMapper.selectById(5L)).thenReturn(source);
        doThrow(new BusinessException(ResultCode.FORBIDDEN, "未选该课程"))
                .when(courseService).assertAccessibleByStudent(1L, 10L);

        assertThatThrownBy(() -> service.getChunkForStudent(1L, 101L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("rag-enabled=false → 404")
    void flagOff_notFound() {
        featureFlags.setRagEnabled(false);
        assertThatThrownBy(() -> service.getChunkForStudent(1L, 101L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
        verify(chunkMapper, never()).selectById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("chunk 不存在 → 404")
    void missingChunk_notFound() {
        when(chunkMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.getChunkForStudent(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
    }
}
