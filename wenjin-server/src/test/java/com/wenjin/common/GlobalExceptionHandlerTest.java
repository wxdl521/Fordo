package com.wenjin.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 单元测试（纯对象，无 Spring 上下文）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("业务异常透传 code 与明细 data（HTTP 200）")
    void businessExceptionCarriesCodeAndDetail() {
        Object detail = "issues-detail";
        BusinessException ex = new BusinessException(ResultCode.GRAPH_VALIDATE_FAIL, "校验失败", detail);

        var resp = handler.handleBusiness(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Result<Object> r = resp.getBody();
        assertThat(r).isNotNull();
        assertThat(r.getCode()).isEqualTo(ResultCode.GRAPH_VALIDATE_FAIL.getCode());
        assertThat(r.getMessage()).isEqualTo("校验失败");
        assertThat(r.getData()).isEqualTo(detail);
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
}
