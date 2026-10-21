package com.wenjin.audit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 解析客户端 IP：优先 {@code X-Forwarded-For} 的首段（反向代理场景），否则 {@code remoteAddr}。
 * 结果截断到 64 字符以适配 {@code ip VARCHAR(64)}。
 *
 * <p>注意：{@code X-Forwarded-For} 可被客户端伪造，审计/登录日志里的 IP 只作排查线索，不做安全判定。</p>
 */
public final class ClientIp {

    static final int MAX_LENGTH = 64;

    private ClientIp() {}

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return truncate(first);
            }
        }
        return truncate(request.getRemoteAddr());
    }

    private static String truncate(String s) {
        return Truncate.to(s, MAX_LENGTH);
    }
}
