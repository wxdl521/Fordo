package com.wenjin.service.rag;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.AiJobVO;
import com.wenjin.dto.DocUploadVO;
import com.wenjin.entity.DocSource;
import com.wenjin.mapper.ChunkNodeMapMapper;
import com.wenjin.mapper.DocChunkMapper;
import com.wenjin.mapper.DocSourceMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.AiJobPayloadSubmissionService;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.DocSourceKind;
import com.wenjin.support.DocSourceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherRagServiceUploadTest {

    @Mock private DocSourceMapper sourceMapper;
    @Mock private DocChunkMapper chunkMapper;
    @Mock private ChunkNodeMapMapper mapMapper;
    @Mock private DocIngestService docIngestService;
    @Mock private CourseAccessService courseAccessService;
    @Mock private AiJobPayloadSubmissionService submissions;

    private TeacherRagService service;

    @BeforeEach
    void setUp() {
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setRagEnabled(true);
        service = new TeacherRagService(sourceMapper, chunkMapper, mapMapper, docIngestService,
                courseAccessService, flags, submissions);
        CurrentUser.set(new CurrentPrincipal(42L, UserRole.TEACHER));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    private static MockMultipartFile file(String name, String body) {
        return new MockMultipartFile("file", name, "application/octet-stream", body.getBytes());
    }

    @Test
    @DisplayName("上传成功：建 INGESTING 来源(含 hash/标题默认文件名) → 派发 DOC_INGEST，key ≤64 且含 sourceId")
    void upload_ok() {
        when(sourceMapper.selectCount(any())).thenReturn(0L);
        when(sourceMapper.insert(any(DocSource.class))).thenAnswer(inv -> {
            ((DocSource) inv.getArgument(0)).setId(77L);
            return 1;
        });
        AiJobVO job = new AiJobVO();
        job.setId(900L);
        when(submissions.submitForCourse(eq(10L), eq(AiJobKind.DOC_INGEST), anyString(), any(), any()))
                .thenReturn(job);

        DocUploadVO vo = service.uploadSource(10L, file("操作系统.PDF".replace("PDF", "pdf"), "x"), null, null);

        assertThat(vo.getSourceId()).isEqualTo(77L);
        assertThat(vo.getJobId()).isEqualTo(900L);
        assertThat(vo.getStatus()).isEqualTo(DocSourceStatus.INGESTING);
        assertThat(vo.getKind()).isEqualTo(DocSourceKind.COURSEWARE);
        assertThat(vo.getTitle()).isEqualTo("操作系统");

        ArgumentCaptor<DocSource> src = ArgumentCaptor.forClass(DocSource.class);
        verify(sourceMapper).insert(src.capture());
        assertThat(src.getValue().getFileHash()).matches("[a-f0-9]{64}");
        assertThat(src.getValue().getStatus()).isEqualTo(DocSourceStatus.INGESTING);
        assertThat(src.getValue().getCreatedBy()).isEqualTo(42L);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> extra = ArgumentCaptor.forClass(Map.class);
        verify(submissions).submitForCourse(eq(10L), eq(AiJobKind.DOC_INGEST), key.capture(), any(), extra.capture());
        assertThat(key.getValue()).startsWith("src:10:77:").hasSizeLessThanOrEqualTo(64);
        assertThat(extra.getValue()).containsEntry("sourceId", 77L);
    }

    @Test
    @DisplayName("同课程同 hash 且非 FAILED/ARCHIVED → 409，不建来源不派发")
    void upload_duplicate409() {
        when(sourceMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.uploadSource(10L, file("a.md", "x"), "t", 0))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.CONFLICT.getCode());
        verify(sourceMapper, never()).insert(any(DocSource.class));
        verify(submissions, never()).submitForCourse(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("非法扩展名 / 空文件 / 非法 kind → 400；rag 关闭 → 404")
    void upload_validation() {
        assertThatThrownBy(() -> service.uploadSource(10L, file("a.exe", "x"), null, null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> service.uploadSource(10L, file("noext", "x"), null, null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> service.uploadSource(10L,
                new MockMultipartFile("file", "a.md", "text/plain", new byte[0]), null, null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> service.uploadSource(10L, file("a.md", "x"), null, 2))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);

        FeatureFlagsProperties off = new FeatureFlagsProperties();
        off.setRagEnabled(false);
        TeacherRagService disabled = new TeacherRagService(sourceMapper, chunkMapper, mapMapper,
                docIngestService, courseAccessService, off, submissions);
        assertThatThrownBy(() -> disabled.uploadSource(10L, file("a.md", "x"), null, null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(404);
        verify(sourceMapper, never()).insert(any(DocSource.class));
    }
}
