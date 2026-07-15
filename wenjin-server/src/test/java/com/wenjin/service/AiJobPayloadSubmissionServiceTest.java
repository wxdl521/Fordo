package com.wenjin.service;

import com.wenjin.dto.AiJobVO;
import com.wenjin.mapper.AiJobMapper;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.AiJobPayloadLifecycle;
import com.wenjin.support.AiJobPayloadStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class AiJobPayloadSubmissionServiceTest {

    @Mock private AiJobService aiJobService;
    @Mock private AiJobPayloadStore payloadStore;
    @Mock private AiJobMapper aiJobMapper;

    @Test
    void courseCodeSubmissionStoresPayloadAndAddsCourseCodeBeforeDispatch() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "graph.xlsx", "application/octet-stream", new byte[]{1});
        when(payloadStore.store(file)).thenReturn(Map.of(
                "fileHash", "a".repeat(64),
                "fileSize", 1,
                "fileName", "graph.xlsx",
                "contentType", "application/octet-stream"));
        AiJobVO expected = new AiJobVO();
        expected.setId(9L);
        when(aiJobService.dispatchByCourseCode(
                eq("COURSE-1"), eq(AiJobKind.GRAPH_IMPORT_EXCEL), eq("request-key"), any()))
                .thenReturn(expected);
        when(aiJobMapper.countJobPayloadReference(9L, "a".repeat(64))).thenReturn(1);
        AiJobPayloadSubmissionService service = new AiJobPayloadSubmissionService(
                aiJobService, payloadStore, new AiJobPayloadLifecycle(), aiJobMapper);

        AiJobVO actual = service.submitForCourseCode(
                "COURSE-1", AiJobKind.GRAPH_IMPORT_EXCEL, "request-key", file);

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(aiJobService).dispatchByCourseCode(
                eq("COURSE-1"), eq(AiJobKind.GRAPH_IMPORT_EXCEL), eq("request-key"), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("courseCode", "COURSE-1")
                .containsEntry("fileHash", "a".repeat(64))
                .containsEntry("fileName", "graph.xlsx");
        verify(aiJobMapper).countJobPayloadReference(9L, "a".repeat(64));
        verify(aiJobMapper, never()).countPayloadReferences("a".repeat(64));
    }

    @Test
    void dispatchFailureDeletesNewPayloadAfterLeavingSubmissionScopeWithoutMaskingFailure() {
        String hash = "b".repeat(64);
        MockMultipartFile file = new MockMultipartFile(
                "file", "questions.xlsx", "application/octet-stream", new byte[]{1});
        when(payloadStore.store(file)).thenReturn(Map.of(
                "fileHash", hash,
                "fileSize", 1,
                "fileName", "questions.xlsx",
                "contentType", "application/octet-stream"));
        IllegalStateException dispatchFailure = new IllegalStateException("dispatch failed");
        when(aiJobService.dispatch(
                eq(7L), eq(AiJobKind.QUESTION_IMPORT_EXCEL), eq("request-key"), any()))
                .thenThrow(dispatchFailure);
        org.mockito.Mockito.doThrow(new IllegalStateException("cleanup failed"))
                .when(payloadStore).deleteQuietly(hash);
        AiJobPayloadSubmissionService service = new AiJobPayloadSubmissionService(
                aiJobService, payloadStore, new AiJobPayloadLifecycle(), aiJobMapper);

        assertThatThrownBy(() -> service.submitForCourse(
                7L, AiJobKind.QUESTION_IMPORT_EXCEL, "request-key", file))
                .isSameAs(dispatchFailure);

        verify(payloadStore).deleteQuietly(hash);
    }

    @Test
    void idempotentDispatchThatDoesNotReferenceNewHashDeletesOrphanAndReturnsOriginalResponse() {
        String hash = "c".repeat(64);
        MockMultipartFile file = new MockMultipartFile(
                "file", "questions.xlsx", "application/octet-stream", new byte[]{1});
        when(payloadStore.store(file)).thenReturn(Map.of(
                "fileHash", hash,
                "fileSize", 1,
                "fileName", "questions.xlsx",
                "contentType", "application/octet-stream"));
        AiJobVO existing = new AiJobVO();
        existing.setId(17L);
        when(aiJobService.dispatch(
                eq(7L), eq(AiJobKind.QUESTION_IMPORT_EXCEL), eq("same-key"), any()))
                .thenReturn(existing);
        AiJobPayloadSubmissionService service = new AiJobPayloadSubmissionService(
                aiJobService, payloadStore, new AiJobPayloadLifecycle(), aiJobMapper);

        AiJobVO response = service.submitForCourse(
                7L, AiJobKind.QUESTION_IMPORT_EXCEL, "same-key", file);

        assertThat(response).isSameAs(existing);
        verify(payloadStore).deleteQuietly(hash);
        verify(aiJobMapper).countJobPayloadReference(17L, hash);
        verify(aiJobMapper).countPayloadReferences(hash);
    }

    @Test
    void dispatchFailureKeepsPayloadWhenCleanupLockRecheckFindsAnotherReference() {
        String hash = "d".repeat(64);
        MockMultipartFile file = new MockMultipartFile(
                "file", "questions.xlsx", "application/octet-stream", new byte[]{1});
        when(payloadStore.store(file)).thenReturn(Map.of(
                "fileHash", hash,
                "fileSize", 1,
                "fileName", "questions.xlsx",
                "contentType", "application/octet-stream"));
        IllegalStateException dispatchFailure = new IllegalStateException("dispatch failed");
        when(aiJobService.dispatch(
                eq(7L), eq(AiJobKind.QUESTION_IMPORT_EXCEL), eq("request-key"), any()))
                .thenThrow(dispatchFailure);
        when(aiJobMapper.countPayloadReferences(hash)).thenReturn(1);
        AiJobPayloadSubmissionService service = new AiJobPayloadSubmissionService(
                aiJobService, payloadStore, new AiJobPayloadLifecycle(), aiJobMapper);

        assertThatThrownBy(() -> service.submitForCourse(
                7L, AiJobKind.QUESTION_IMPORT_EXCEL, "request-key", file))
                .isSameAs(dispatchFailure);

        verify(aiJobMapper).countPayloadReferences(hash);
        verify(payloadStore, never()).deleteQuietly(hash);
    }
}
