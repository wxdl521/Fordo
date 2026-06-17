package com.wenjin.common;

/**
 * 业务状态码枚举。
 */
public enum ResultCode {

    /** 成功 */
    SUCCESS(0, "成功"),
    /** 参数错误 */
    BAD_REQUEST(400, "请求参数错误"),
    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),
    /** 图谱导入校验失败 */
    GRAPH_VALIDATE_FAIL(1001, "图谱校验失败"),
    /** AI 服务调用失败 */
    AI_ERROR(1002, "AI服务调用失败"),
    /** 用户名已存在 */
    USER_EXISTS(1003, "用户名已存在"),
    /** 用户名或密码错误 */
    LOGIN_FAIL(1004, "用户名或密码错误"),
    /** 服务器内部错误 */
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
