package com.wenjin.common;

import com.wenjin.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器。把各类异常统一转换为 Result 返回体，并映射真实 HTTP 状态；
 * 内部错误不向客户端泄露异常明细。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String SAFE_INTERNAL_MESSAGE = "服务器内部错误";
    private static final String STABLE_CONFLICT_MESSAGE = "数据已存在，请勿重复提交";

    /**
     * 业务异常：按 {@link ResultCode#httpStatusOf(int)} 映射真实 HTTP 状态。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Object>> handleBusiness(BusinessException ex) {
        String requestId = currentRequestId();
        log.warn("业务异常: code={}, message={}, requestId={}", ex.getCode(), ex.getMessage(), requestId);
        Result<Object> body = Result.fail(ex.getCode(), ex.getMessage(), ex.getDetail());
        int http = ResultCode.httpStatusOf(ex.getCode());
        return ResponseEntity.status(http).body(body);
    }

    /** 参数校验异常（@Valid 请求体） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST.getCode(), msg));
    }

    /** 方法参数约束（@Validated 路径/查询参数） */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String msg = ex.getConstraintViolations().stream()
                .findFirst()
                .map(v -> {
                    String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
                    // 取方法参数名末段，避免整段 method.param 过长
                    int dot = path.lastIndexOf('.');
                    String field = dot >= 0 ? path.substring(dot + 1) : path;
                    return (field.isEmpty() ? "" : field + ": ") + v.getMessage();
                })
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST.getCode(), msg));
    }

    /** 查询/路径参数类型不匹配 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName() == null ? "参数" : ex.getName();
        String msg = name + ": 类型不正确";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST.getCode(), msg));
    }

    /** 缺少必填查询/表单参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        String msg = "缺少必填参数: " + ex.getParameterName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST.getCode(), msg));
    }

    /** 请求体不可读（空 body / 非法 JSON）：返回干净的 400，而非兜底 500 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Object>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体不可读 requestId={}: {}", currentRequestId(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST.getCode(), "请求体不能为空或格式错误"));
    }

    /**
     * 数据完整性冲突（如唯一键）。业务码 {@link ResultCode#CONFLICT}，HTTP 409；
     * 唯一约束返回稳定冲突文案。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        String requestId = currentRequestId();
        log.warn("数据完整性冲突 requestId={}: {}", requestId, ex.getMostSpecificCause() != null
                ? ex.getMostSpecificCause().getMessage()
                : ex.getMessage());
        String msg = isUniqueViolation(ex) ? STABLE_CONFLICT_MESSAGE : "数据约束冲突，请检查提交内容";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.fail(ResultCode.CONFLICT.getCode(), msg));
    }

    /** 兜底异常：固定文案，不泄露内部明细；日志带 requestId */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleOther(Exception ex) {
        String requestId = currentRequestId();
        log.error("未捕获异常 requestId={}", requestId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.INTERNAL_ERROR.getCode(), SAFE_INTERNAL_MESSAGE));
    }

    static boolean isUniqueViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String text = cause != null ? cause.getMessage() : ex.getMessage();
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("duplicate")
                || lower.contains("unique")
                || lower.contains("uk_")
                || lower.contains("duplicate entry");
    }

    private static String currentRequestId() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            return RequestIdFilter.currentRequestId(request);
        } catch (Exception ignored) {
            return null;
        }
    }
}
