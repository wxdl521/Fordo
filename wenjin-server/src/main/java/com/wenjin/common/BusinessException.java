package com.wenjin.common;

/**
 * 业务异常。携带状态码、提示信息，以及可选的明细数据（如校验错误列表）。
 * 由全局异常处理器统一转换为 Result。
 */
public class BusinessException extends RuntimeException {

    private final int code;
    /** 可选的错误明细（如导入校验失败时的逐条错误） */
    private final transient Object detail;

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
        this.detail = null;
    }

    public BusinessException(ResultCode resultCode, String message, Object detail) {
        super(message);
        this.code = resultCode.getCode();
        this.detail = detail;
    }

    public int getCode() {
        return code;
    }

    public Object getDetail() {
        return detail;
    }
}
