package com.wenjin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求分配 requestId：优先透传客户端 {@code X-Request-Id}，否则生成 UUID。
 * 写入请求属性、响应头与 MDC，便于日志关联与安全错误回传。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTR = "requestId";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        } else {
            // 限制长度，防止异常头污染日志
            requestId = requestId.trim();
            if (requestId.length() > 64) {
                requestId = requestId.substring(0, 64);
            }
        }
        request.setAttribute(ATTR, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 从当前请求属性读取 requestId（无请求上下文时返回 null）。 */
    public static String currentRequestId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object v = request.getAttribute(ATTR);
        return v == null ? null : v.toString();
    }
}
