package com.wenjin.common;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 单元测试（纯对象，无 Spring 上下文）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("业务异常透传 code 与明细 data，并映射真实 HTTP 状态")
    void businessExceptionCarriesCodeAndDetail() {
        Object detail = "issues-detail";
        BusinessException ex = new BusinessException(ResultCode.GRAPH_VALIDATE_FAIL, "校验失败", detail);

        var resp = handler.handleBusiness(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(ResultCode.GRAPH_VALIDATE_FAIL.getHttpStatus());
        Result<Object> r = resp.getBody();
        assertThat(r).isNotNull();
        assertThat(r.getCode()).isEqualTo(ResultCode.GRAPH_VALIDATE_FAIL.getCode());
        assertThat(r.getMessage()).isEqualTo("校验失败");
        assertThat(r.getData()).isEqualTo(detail);
    }

    @Test
    @DisplayName("UNAUTHORIZED 映射为 HTTP 401")
    void unauthorizedMapsTo401() {
        BusinessException ex = new BusinessException(ResultCode.UNAUTHORIZED, "未登录");
        var resp = handler.handleBusiness(ex);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("FORBIDDEN 映射为 HTTP 403")
    void forbiddenMapsTo403() {
        BusinessException ex = new BusinessException(ResultCode.FORBIDDEN, "无权限");
        var resp = handler.handleBusiness(ex);
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("SERVICE_UNAVAILABLE 映射为 HTTP 503")
    void serviceUnavailableMapsTo503() {
        BusinessException ex = new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "固定诊断功能暂未开放");

        var resp = handler.handleBusiness(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("请求体不可读（空 body / 非法 JSON）映射为 HTTP 400 且业务码 400")
    void unreadableBodyMapsTo400() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("required body is missing", (HttpInputMessage) null);

        var resp = handler.handleNotReadable(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("ConstraintViolationException → 400")
    void constraintViolationMapsTo400() {
        ConstraintViolationException ex =
                new ConstraintViolationException("bad", Collections.emptySet());
        var resp = handler.handleConstraintViolation(ex);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("MissingServletRequestParameterException → 400")
    void missingParamMapsTo400() {
        var resp = handler.handleMissingParam(
                new MissingServletRequestParameterException("courseId", "Long"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("courseId");
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException → 400")
    void typeMismatchMapsTo400() throws Exception {
        MethodParameter param = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("sample", Long.class), 0);
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("x", Long.class, "courseId", param, null);
        var resp = handler.handleTypeMismatch(ex);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("courseId");
    }

    @Test
    @DisplayName("唯一约束冲突 → 409 且稳定文案")
    void uniqueViolationMapsTo409StableMessage() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute",
                new java.sql.SQLIntegrityConstraintViolationException(
                        "Duplicate entry 'alice' for key 'uk_username'"));
        var resp = handler.handleDataIntegrity(ex);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().getMessage()).isEqualTo("数据已存在，请勿重复提交");
    }

    @Test
    @DisplayName("兜底异常不泄露内部明细")
    void internalErrorDoesNotLeakMessage() {
        var resp = handler.handleOther(new RuntimeException("secret stack detail"));
        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody().getMessage()).isEqualTo("服务器内部错误");
        assertThat(resp.getBody().getMessage()).doesNotContain("secret");
    }

    @SuppressWarnings("unused")
    private static void sample(Long courseId) {
        // reflection target for MethodParameter
    }
}
