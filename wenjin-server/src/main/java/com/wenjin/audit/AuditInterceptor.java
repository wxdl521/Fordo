package com.wenjin.audit;

import com.wenjin.config.CurrentUser;
import com.wenjin.config.RequestIdFilter;
import com.wenjin.entity.AuditLog;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.AuditLogMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 审计拦截器：请求完成后（{@code afterCompletion}）读取控制器方法上的 {@link Audited}，写一条 {@code audit_log}。
 *
 * <h2>注册顺序（见 {@code WebConfig}）</h2>
 * <p>必须注册在 {@code AuthContextInterceptor} <em>之后</em>：Spring 逆序执行 {@code afterCompletion}，
 * 后注册的先执行，这样本拦截器运行时 {@link CurrentUser} 还没被 authContext 清掉。
 * 同时注册在教师/管理员角色拦截器 <em>之前</em>，这样已登录但被它们以 403 拒绝的越权请求也会留下一条失败记录（匿名请求不记）
 * （preHandle 没跑完的拦截器不会收到 afterCompletion）。</p>
 *
 * <h2>失败策略</h2>
 * <p>审计写库失败只打 warn 日志，绝不影响业务请求；同步写（量小），不做异步队列。</p>
 *
 * <p>{@code success = 状态码 < 400 且 ex == null}。{@code GlobalExceptionHandler} 已处理的异常
 * 到这里 ex 为 null，靠响应状态码判断。</p>
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    /** target_id 候选路径变量，按优先级取第一个存在的。 */
    private static final String[] TARGET_ID_KEYS = {"id", "courseId", "draftId"};

    private static final int MAX_PATH = 255;
    private static final int MAX_TARGET_ID = 64;
    private static final int MAX_OPERATOR_NAME = 64;
    private static final int MAX_REQUEST_ID = 64;

    private final AuditLogMapper auditLogMapper;
    private final SysUserMapper userMapper;

    public AuditInterceptor(AuditLogMapper auditLogMapper, SysUserMapper userMapper) {
        this.auditLogMapper = auditLogMapper;
        this.userMapper = userMapper;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            if (!(handler instanceof HandlerMethod handlerMethod)) {
                return;
            }
            Audited audited = handlerMethod.getMethodAnnotation(Audited.class);
            if (audited == null) {
                return;
            }
            // 匿名请求（必然 401）不记：否则未登录刷接口即可无限写审计表；已登录越权（403）照记
            if (CurrentUser.getPrincipal() == null) {
                return;
            }
            auditLogMapper.insert(buildRecord(audited, request, response, ex));
        } catch (Exception e) {
            // 审计是旁路：任何失败都不能影响已经完成的业务响应
            log.warn("审计日志写入失败（已忽略） uri={} err={}", request.getRequestURI(), e.toString());
        }
    }

    private AuditLog buildRecord(Audited audited, HttpServletRequest request,
                                 HttpServletResponse response, Exception ex) {
        AuditLog rec = new AuditLog();

        CurrentPrincipal principal = CurrentUser.getPrincipal();
        if (principal != null) {
            rec.setOperatorId(principal.userId());
            rec.setOperatorRole(principal.role().code());
            rec.setOperatorName(lookupUsername(principal.userId()));
        }

        rec.setAction(audited.action());
        rec.setTargetType(audited.target().isBlank() ? null : audited.target());
        rec.setTargetId(Truncate.to(resolveTargetId(request), MAX_TARGET_ID));
        rec.setHttpMethod(request.getMethod());
        rec.setPath(Truncate.to(request.getRequestURI(), MAX_PATH));

        int status = response.getStatus();
        boolean unhandled = ex != null;
        if (unhandled && status < 400) {
            // 异常没被任何处理器接住时响应状态还是默认的 200，真实结果是 500
            status = 500;
        }
        rec.setHttpStatus(status);
        rec.setSuccess(!unhandled && status < 400 ? 1 : 0);

        rec.setIp(ClientIp.resolve(request));
        rec.setRequestId(Truncate.to(RequestIdFilter.currentRequestId(request), MAX_REQUEST_ID));
        return rec;
    }

    /** 用户名只用于冗余留痕；查不到或查询失败都不阻止记录。 */
    private String lookupUsername(long userId) {
        try {
            SysUser user = userMapper.selectById(userId);
            return user == null ? null : Truncate.to(user.getUsername(), MAX_OPERATOR_NAME);
        } catch (Exception e) {
            log.warn("审计日志查询操作人失败（已忽略） userId={} err={}", userId, e.toString());
            return null;
        }
    }

    private static String resolveTargetId(HttpServletRequest request) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attr instanceof Map<?, ?> vars)) {
            return null;
        }
        for (String key : TARGET_ID_KEYS) {
            Object value = vars.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }
}
