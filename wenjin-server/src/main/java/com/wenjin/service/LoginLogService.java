package com.wenjin.service;

import com.wenjin.audit.Truncate;
import com.wenjin.entity.LoginLog;
import com.wenjin.mapper.LoginLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 登录日志写入。成功与失败都记；<strong>不记录密码</strong>（方法签名里根本没有这个参数）。
 *
 * <p>写库失败只打 warn，绝不影响登录请求本身：登录日志是旁路观测，不是登录流程的一部分。</p>
 */
@Service
public class LoginLogService {

    private static final Logger log = LoggerFactory.getLogger(LoginLogService.class);

    static final int MAX_USERNAME = 64;
    static final int MAX_FAIL_REASON = 64;
    static final int MAX_IP = 64;
    static final int MAX_USER_AGENT = 255;

    private final LoginLogMapper loginLogMapper;

    public LoginLogService(LoginLogMapper loginLogMapper) {
        this.loginLogMapper = loginLogMapper;
    }

    /**
     * @param userId     已定位到的用户 ID；用户名不存在时为 null
     * @param username   尝试登录的用户名（超过 64 字符被截断）
     * @param success    是否登录成功
     * @param failReason 失败原因（BAD_CREDENTIALS / DISABLED）；成功时传 null
     * @param ip         客户端 IP
     * @param userAgent  浏览器 User-Agent（超过 255 字符被截断）
     */
    public void record(Long userId, String username, boolean success, String failReason,
                       String ip, String userAgent) {
        try {
            LoginLog row = new LoginLog();
            row.setUserId(userId);
            row.setUsername(Truncate.to(username == null ? "" : username, MAX_USERNAME));
            row.setSuccess(success ? 1 : 0);
            row.setFailReason(success ? null : Truncate.to(failReason, MAX_FAIL_REASON));
            row.setIp(Truncate.to(ip, MAX_IP));
            row.setUserAgent(Truncate.to(userAgent, MAX_USER_AGENT));
            loginLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("登录日志写入失败（已忽略） success={} err={}", success, e.toString());
        }
    }
}
