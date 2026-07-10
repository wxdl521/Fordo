package com.wenjin.common;

/**
 * 业务状态码枚举。携带业务 code、默认提示，以及对应的 HTTP 状态。
 */
public enum ResultCode {

    /** 成功 */
    SUCCESS(0, "成功", 200),
    /** 参数错误 */
    BAD_REQUEST(400, "请求参数错误", 400),
    /** 未登录 / 缺少身份 */
    UNAUTHORIZED(401, "未登录或登录已失效，请重新登录", 401),
    /** 已登录但无权限（如非教师访问教师端） */
    FORBIDDEN(403, "无访问权限", 403),
    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在", 404),
    /** 数据冲突（唯一键 / 完整性约束等） */
    CONFLICT(409, "数据冲突", 409),
    /** 图谱导入校验失败 */
    GRAPH_VALIDATE_FAIL(1001, "图谱校验失败", 422),
    /** AI 服务调用失败 */
    AI_ERROR(1002, "AI服务调用失败", 502),
    /** 用户名已存在 */
    USER_EXISTS(1003, "用户名已存在", 409),
    /** 用户名或密码错误 */
    LOGIN_FAIL(1004, "用户名或密码错误", 401),
    /** 服务器内部错误 */
    INTERNAL_ERROR(500, "服务器内部错误", 500),
    /** 功能暂时不可用（特性开关关闭等） */
    SERVICE_UNAVAILABLE(503, "服务暂时不可用", 503);

    private final int code;
    private final String message;
    /** 对应的 HTTP 状态码 */
    private final int httpStatus;

    ResultCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** 按业务 code 反查；未知时返回 null。 */
    public static ResultCode fromCode(int code) {
        for (ResultCode rc : values()) {
            if (rc.code == code) {
                return rc;
            }
        }
        return null;
    }

    /**
     * 业务 code → HTTP 状态。已知枚举用其映射；未知非 0 默认 400；0 为 200。
     */
    public static int httpStatusOf(int code) {
        if (code == SUCCESS.code) {
            return SUCCESS.httpStatus;
        }
        ResultCode rc = fromCode(code);
        if (rc != null) {
            return rc.httpStatus;
        }
        return BAD_REQUEST.httpStatus;
    }
}
