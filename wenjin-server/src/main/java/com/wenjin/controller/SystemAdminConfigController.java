package com.wenjin.controller;

import com.wenjin.audit.AuditInterceptor;
import com.wenjin.audit.Audited;
import com.wenjin.common.Result;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.AdminAiTestRequest;
import com.wenjin.dto.AdminAiTestVO;
import com.wenjin.dto.AdminConfigGroupVO;
import com.wenjin.dto.AdminConfigItemVO;
import com.wenjin.dto.AdminConfigUpdateRequest;
import com.wenjin.dto.AdminDataResetRequest;
import com.wenjin.dto.AdminDataResetVO;
import com.wenjin.service.AdminDataResetService;
import com.wenjin.sysconfig.AiConnectionTester;
import com.wenjin.sysconfig.SysConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理后台「系统配置」与「数据维护」（受 {@code AdminAuthInterceptor} 保护，仅系统管理员）。
 *
 * <p>key 含点号（如 {@code wenjin.ai.model}），路径变量用 {@code {key:.+}} 防止被当作后缀截断。
 * 审计 target_id：配置项取路径变量 key；AI 测试 / 数据重置的目标在请求体里，经
 * {@link AuditInterceptor#TARGET_ID_ATTRIBUTE} 传给审计。审计里不记录任何配置值。</p>
 */
@RestController
@RequestMapping("/api/system-admin")
@Validated
public class SystemAdminConfigController {

    private final SysConfigService configService;
    private final AiConnectionTester aiConnectionTester;
    private final AdminDataResetService dataResetService;

    public SystemAdminConfigController(SysConfigService configService, AiConnectionTester aiConnectionTester,
                                       AdminDataResetService dataResetService) {
        this.configService = configService;
        this.aiConnectionTester = aiConnectionTester;
        this.dataResetService = dataResetService;
    }

    /** GET /api/system-admin/configs：白名单配置项按分组返回（secret 只给 configured + 掩码）。 */
    @GetMapping("/configs")
    public Result<List<AdminConfigGroupVO>> list() {
        return Result.ok(configService.list());
    }

    /** PUT /api/system-admin/configs/{key}：{@code {value}} 保存覆盖值，立即生效。 */
    @Audited(action = "CONFIG_UPDATE", target = "CONFIG")
    @PutMapping("/configs/{key:.+}")
    public Result<AdminConfigItemVO> update(@PathVariable("key") String key,
                                            @Valid @RequestBody AdminConfigUpdateRequest request) {
        return Result.ok(configService.update(key, request.getValue(), CurrentUser.require().userId()));
    }

    /** DELETE /api/system-admin/configs/{key}：删除覆盖值，恢复默认。 */
    @Audited(action = "CONFIG_RESET", target = "CONFIG")
    @DeleteMapping("/configs/{key:.+}")
    public Result<AdminConfigItemVO> reset(@PathVariable("key") String key) {
        return Result.ok(configService.reset(key));
    }

    /** POST /api/system-admin/configs/ai-test：{@code {target: text|vision}} 用当前生效配置测试连接。 */
    @Audited(action = "AI_TEST", target = "CONFIG")
    @PostMapping("/configs/ai-test")
    public Result<AdminAiTestVO> aiTest(@Valid @RequestBody AdminAiTestRequest request,
                                        HttpServletRequest httpRequest) {
        httpRequest.setAttribute(AuditInterceptor.TARGET_ID_ATTRIBUTE, request.getTarget());
        return Result.ok(aiConnectionTester.test(request.getTarget()));
    }

    /** POST /api/system-admin/data-reset：{@code {courseId, confirmText}} 按课程清空学生学习数据。 */
    @Audited(action = "DATA_RESET", target = "COURSE")
    @PostMapping("/data-reset")
    public Result<AdminDataResetVO> dataReset(@Valid @RequestBody AdminDataResetRequest request,
                                              HttpServletRequest httpRequest) {
        httpRequest.setAttribute(AuditInterceptor.TARGET_ID_ATTRIBUTE, String.valueOf(request.getCourseId()));
        return Result.ok(dataResetService.resetCourse(request.getCourseId(), request.getConfirmText()));
    }
}
