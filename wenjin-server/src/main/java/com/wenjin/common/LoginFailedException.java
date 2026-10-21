package com.wenjin.common;

/**
 * 登录失败（业务码 {@link ResultCode#LOGIN_FAIL}，HTTP 401）。
 *
 * <p>在 {@link BusinessException} 之上多带两样东西，只供登录日志使用、<strong>不会</strong>出现在响应里
 * （全局异常处理器只输出 code/message/detail）：失败原因，以及已查到的用户 ID（用户名不存在时为 null），
 * 省得登录日志再按用户名多查一次库。</p>
 */
public class LoginFailedException extends BusinessException {

    /** 登录失败原因（落库字符串即枚举名）。 */
    public enum Reason {
        /** 用户名不存在或密码错误（对外同一文案，防用户枚举） */
        BAD_CREDENTIALS,
        /** 密码正确但账号已被禁用 */
        DISABLED
    }

    private final Reason reason;
    private final Long userId;

    public LoginFailedException(Reason reason, Long userId, String message) {
        super(ResultCode.LOGIN_FAIL, message);
        this.reason = reason;
        this.userId = userId;
    }

    public Reason getReason() {
        return reason;
    }

    /** 已定位到的用户 ID；用户名不存在时为 null。 */
    public Long getUserId() {
        return userId;
    }
}
