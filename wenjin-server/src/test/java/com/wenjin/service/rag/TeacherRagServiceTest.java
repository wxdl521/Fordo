package com.wenjin.service.rag;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.entity.ChunkNodeMap;
import com.wenjin.mapper.ChunkNodeMapMapper;
import com.wenjin.mapper.DocChunkMapper;
import com.wenjin.mapper.DocSourceMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.support.ChunkMapReviewStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherRagServiceTest {

    @Mock private DocSourceMapper sourceMapper;
    @Mock private DocChunkMapper chunkMapper;
    @Mock private ChunkNodeMapMapper mapMapper;
    @Mock private DocIngestService docIngestService;
    @Mock private CourseAccessService courseAccessService;
    @Mock private com.wenjin.service.AiJobPayloadSubmissionService payloadSubmissionService;

    private FeatureFlagsProperties featureFlags;
    private TeacherRagService service;

    @BeforeEach
    void setUp() {
        featureFlags = new FeatureFlagsProperties();
        featureFlags.setRagEnabled(true);
        service = new TeacherRagService(
                sourceMapper, chunkMapper, mapMapper, docIngestService, courseAccessService, featureFlags,
                payloadSubmissionService);
        CurrentUser.set(new CurrentPrincipal(42L, UserRole.TEACHER));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("approve：写入 APPROVED + reviewedBy/At")
    void approve_setsStatusAndReviewer() {
        ChunkNodeMap row = new ChunkNodeMap();
        row.setId(7L);
        row.setCourseId(10L);
        row.setReviewStatus(ChunkMapReviewStatus.PENDING);
        row.setConfidence(new BigDecimal("0.900"));
        when(mapMapper.selectById(7L)).thenReturn(row);
        doNothing().when(courseAccessService).assertManageCourse(10L);

        service.approve(7L);

        ArgumentCaptor<ChunkNodeMap> cap = ArgumentCaptor.forClass(ChunkNodeMap.class);
        verify(mapMapper).updateById(cap.capture());
        ChunkNodeMap updated = cap.getValue();
        assertThat(updated.getReviewStatus()).isEqualTo(ChunkMapReviewStatus.APPROVED);
        assertThat(updated.getReviewedBy()).isEqualTo(42L);
        assertThat(updated.getReviewedAt()).isNotNull();
        assertThat(updated.getReviewedAt()).isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(1));
    }

    @Test
    @DisplayName("reject：写入 REJECTED + reviewedBy")
    void reject_setsRejected() {
        ChunkNodeMap row = new ChunkNodeMap();
        row.setId(8L);
        row.setCourseId(10L);
        row.setReviewStatus(ChunkMapReviewStatus.PENDING);
        when(mapMapper.selectById(8L)).thenReturn(row);
        doNothing().when(courseAccessService).assertManageCourse(10L);

        service.reject(8L);

        ArgumentCaptor<ChunkNodeMap> cap = ArgumentCaptor.forClass(ChunkNodeMap.class);
        verify(mapMapper).updateById(cap.capture());
        assertThat(cap.getValue().getReviewStatus()).isEqualTo(ChunkMapReviewStatus.REJECTED);
        assertThat(cap.getValue().getReviewedBy()).isEqualTo(42L);
    }

    @Test
    @DisplayName("approve：rag-enabled=false → 404")
    void approve_flagOff_notFound() {
        featureFlags.setRagEnabled(false);
        assertThatThrownBy(() -> service.approve(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
        verify(mapMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("approve：映射不存在 → 404")
    void approve_missing_notFound() {
        when(mapMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.approve(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
        verify(courseAccessService, never()).assertManageCourse(any());
    }
}
