package com.wenjin.controller;

import com.wenjin.audit.AuditInterceptor;
import com.wenjin.common.BusinessException;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AdminAuthInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.AdminAiTestVO;
import com.wenjin.dto.AdminConfigGroupVO;
import com.wenjin.dto.AdminConfigItemVO;
import com.wenjin.dto.AdminDataResetVO;
import com.wenjin.entity.AuditLog;
import com.wenjin.mapper.AuditLogMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.AdminDataResetService;
import com.wenjin.sysconfig.AiConnectionTester;
import com.wenjin.sysconfig.SysConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SystemAdminConfigController：契约（路径 / 字段）+ 点号 key 不被截断 + 管理员鉴权 + 审计 target_id 且不记值。
 */
@ExtendWith(MockitoExtension.class)
class SystemAdminConfigControllerTest {

    private static final String BASE = "/api/system-admin";
    private static final String SECRET = "sk-very-secret-123456789";

    @Mock
    private SysConfigService configService;
    @Mock
    private AiConnectionTester aiConnectionTester;
    @Mock
    private AdminDataResetService dataResetService;
    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private SysUserMapper userMapper;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new SystemAdminConfigController(configService, aiConnectionTester, dataResetService))
                // 与 WebConfig 同序：审计在管理员鉴权之前
                .addInterceptors(new AuditInterceptor(auditLogMapper, userMapper), new AdminAuthInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        CurrentUser.set(new CurrentPrincipal(1L, UserRole.ADMIN));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    // ───────────────────────── GET /configs ─────────────────────────

    @Test
    @DisplayName("GET /configs：[{group, items:[{key,label,description,type,value,defaultValue,overridden,...}]}]")
    void list() throws Exception {
        AdminConfigItemVO model = item("wenjin.ai.model", "string");
        model.setValue("deepseek-v4-pro");
        model.setDefaultValue("deepseek-v4-pro");
        AdminConfigItemVO key = item("wenjin.ai.api-key", "secret");
        key.setConfigured(true);
        key.setMasked("abcd");
        key.setOverridden(true);
        key.setUpdatedAt("2026-10-21T09:30:00");
        key.setUpdatedBy(1L);
        AdminConfigItemVO temp = item("wenjin.ai.temperature", "number");
        temp.setValue(0.4);
        temp.setMin(0.0);
        temp.setMax(2.0);
        temp.setNullable(true);
        when(configService.list()).thenReturn(List.of(new AdminConfigGroupVO("AI 文本", List.of(model, key, temp))));

        mvc.perform(get(BASE + "/configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].group").value("AI 文本"))
                .andExpect(jsonPath("$.data[0].items[0].key").value("wenjin.ai.model"))
                .andExpect(jsonPath("$.data[0].items[0].label").value("标签"))
                .andExpect(jsonPath("$.data[0].items[0].description").value("说明"))
                .andExpect(jsonPath("$.data[0].items[0].type").value("string"))
                .andExpect(jsonPath("$.data[0].items[0].value").value("deepseek-v4-pro"))
                .andExpect(jsonPath("$.data[0].items[0].defaultValue").value("deepseek-v4-pro"))
                .andExpect(jsonPath("$.data[0].items[0].overridden").value(false))
                .andExpect(jsonPath("$.data[0].items[0].updatedAt").value((Object) null))
                .andExpect(jsonPath("$.data[0].items[0].updatedBy").value((Object) null))
                .andExpect(jsonPath("$.data[0].items[0].configured").doesNotExist())
                .andExpect(jsonPath("$.data[0].items[0].masked").doesNotExist())
                .andExpect(jsonPath("$.data[0].items[1].type").value("secret"))
                .andExpect(jsonPath("$.data[0].items[1].value").value((Object) null))
                .andExpect(jsonPath("$.data[0].items[1].defaultValue").value((Object) null))
                .andExpect(jsonPath("$.data[0].items[1].configured").value(true))
                .andExpect(jsonPath("$.data[0].items[1].masked").value("abcd"))
                .andExpect(jsonPath("$.data[0].items[1].overridden").value(true))
                .andExpect(jsonPath("$.data[0].items[1].updatedAt").value("2026-10-21T09:30:00"))
                .andExpect(jsonPath("$.data[0].items[1].updatedBy").value(1))
                .andExpect(jsonPath("$.data[0].items[2].min").value(0.0))
                .andExpect(jsonPath("$.data[0].items[2].max").value(2.0))
                .andExpect(jsonPath("$.data[0].items[2].nullable").value(true))
                .andExpect(jsonPath("$.data[0].items[2].options").doesNotExist());
    }

    // ───────────────────────── PUT / DELETE /configs/{key} ─────────────────────────

    @Test
    @DisplayName("PUT /configs/{key}：含点号的 key 完整透传（不被当后缀截断），value 按 JSON 原类型传给 service，操作人=当前管理员")
    void update_dottedKeyAndTypedValue() throws Exception {
        AdminConfigItemVO vo = item("wenjin.ai.model", "string");
        vo.setValue("deepseek-chat");
        vo.setOverridden(true);
        when(configService.update("wenjin.ai.model", "deepseek-chat", 1L)).thenReturn(vo);
        when(configService.update("wenjin.features.rag-enabled", false, 1L))
                .thenReturn(item("wenjin.features.rag-enabled", "bool"));
        when(configService.update("wenjin.limits.companion.max-input-chars", 2000, 1L))
                .thenReturn(item("wenjin.limits.companion.max-input-chars", "number"));

        mvc.perform(put(BASE + "/configs/wenjin.ai.model").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"deepseek-chat\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.key").value("wenjin.ai.model"))
                .andExpect(jsonPath("$.data.value").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.overridden").value(true));
        mvc.perform(put(BASE + "/configs/wenjin.features.rag-enabled").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":false}"))
                .andExpect(status().isOk());
        mvc.perform(put(BASE + "/configs/wenjin.limits.companion.max-input-chars")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":2000}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT：白名单外 / 校验不过 → service 的 400 原样透出；缺 body → 400")
    void update_errors() throws Exception {
        when(configService.update("wenjin.auth.secret", "x", 1L))
                .thenThrow(new BusinessException(ResultCode.BAD_REQUEST, "不支持的配置项：wenjin.auth.secret"));

        mvc.perform(put(BASE + "/configs/wenjin.auth.secret").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不支持的配置项：wenjin.auth.secret"));
        mvc.perform(put(BASE + "/configs/wenjin.ai.model").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /configs/{key}：恢复默认，返回单项")
    void reset() throws Exception {
        AdminConfigItemVO vo = item("wenjin.ai.api-key", "secret");
        vo.setConfigured(false);
        when(configService.reset("wenjin.ai.api-key")).thenReturn(vo);

        mvc.perform(delete(BASE + "/configs/wenjin.ai.api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.key").value("wenjin.ai.api-key"))
                .andExpect(jsonPath("$.data.overridden").value(false))
                .andExpect(jsonPath("$.data.configured").value(false));
    }

    // ───────────────────────── 审计 ─────────────────────────

    @Test
    @DisplayName("审计：CONFIG_UPDATE / CONFIG_RESET 的 target_id = key；记录里不出现提交的配置值")
    void audit_targetIdIsKey_andNoValue() throws Exception {
        when(configService.update("wenjin.ai.api-key", SECRET, 1L)).thenReturn(item("wenjin.ai.api-key", "secret"));
        when(configService.reset("wenjin.ai.api-key")).thenReturn(item("wenjin.ai.api-key", "secret"));

        mvc.perform(put(BASE + "/configs/wenjin.ai.api-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete(BASE + "/configs/wenjin.ai.api-key"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper, atLeastOnce()).insert(captor.capture());
        List<AuditLog> logs = captor.getAllValues();
        assertThat(logs).extracting(AuditLog::getAction).containsExactly("CONFIG_UPDATE", "CONFIG_RESET");
        assertThat(logs).extracting(AuditLog::getTargetType).containsOnly("CONFIG");
        assertThat(logs).extracting(AuditLog::getTargetId).containsOnly("wenjin.ai.api-key");
        assertThat(logs).allSatisfy(l -> assertThat(l.toString()).doesNotContain(SECRET).doesNotContain("123456789"));
    }

    @Test
    @DisplayName("审计：AI_TEST 的 target_id = text/vision，DATA_RESET 的 target_id = 课程 id（目标在请求体里）")
    void audit_bodyTargets() throws Exception {
        when(aiConnectionTester.test("vision")).thenReturn(new AdminAiTestVO(true, 12, "glm-5v-turbo", null));
        when(dataResetService.resetCourse(5L, "软件工程")).thenReturn(new AdminDataResetVO(Map.of(), 0));

        mvc.perform(post(BASE + "/configs/ai-test").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"vision\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(BASE + "/data-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5,\"confirmText\":\"软件工程\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper, atLeastOnce()).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditLog::getAction, AuditLog::getTargetType, AuditLog::getTargetId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("AI_TEST", "CONFIG", "vision"),
                        org.assertj.core.groups.Tuple.tuple("DATA_RESET", "COURSE", "5"));
    }

    // ───────────────────────── POST /configs/ai-test ─────────────────────────

    @Test
    @DisplayName("POST /configs/ai-test：{ok, latencyMs, model, error?}；target 只能 text / vision")
    void aiTest() throws Exception {
        when(aiConnectionTester.test("text")).thenReturn(new AdminAiTestVO(false, 321, "deepseek-v4-pro", "HTTP 401 ****"));

        mvc.perform(post(BASE + "/configs/ai-test").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.latencyMs").value(321))
                .andExpect(jsonPath("$.data.model").value("deepseek-v4-pro"))
                .andExpect(jsonPath("$.data.error").value("HTTP 401 ****"));

        when(aiConnectionTester.test("vision")).thenReturn(new AdminAiTestVO(true, 88, "glm-5v-turbo", null));
        mvc.perform(post(BASE + "/configs/ai-test").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"vision\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.error").doesNotExist());

        for (String body : List.of("{}", "{\"target\":\"\"}", "{\"target\":\"audio\"}")) {
            mvc.perform(post(BASE + "/configs/ai-test").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ───────────────────────── POST /data-reset ─────────────────────────

    @Test
    @DisplayName("POST /data-reset：{courseId, confirmText} 透传，返回 {deleted:{表:行数}, currentPathCleared}")
    void dataReset() throws Exception {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("answer_record", 120);
        deleted.put("student_mastery", 39);
        when(dataResetService.resetCourse(5L, "软件工程")).thenReturn(new AdminDataResetVO(deleted, 6));

        mvc.perform(post(BASE + "/data-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5,\"confirmText\":\"软件工程\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted.answer_record").value(120))
                .andExpect(jsonPath("$.data.deleted.student_mastery").value(39))
                .andExpect(jsonPath("$.data.currentPathCleared").value(6));
    }

    @Test
    @DisplayName("POST /data-reset：缺 courseId / courseId≤0 / 缺确认文本 → 400 不进 service；confirmText 不符与 prod 禁用原样透出")
    void dataReset_errors() throws Exception {
        for (String body : List.of("{\"confirmText\":\"软件工程\"}", "{\"courseId\":0,\"confirmText\":\"软件工程\"}",
                "{\"courseId\":5}", "{\"courseId\":5,\"confirmText\":\"  \"}")) {
            mvc.perform(post(BASE + "/data-reset").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(dataResetService);

        when(dataResetService.resetCourse(5L, "软件"))
                .thenThrow(new BusinessException(ResultCode.BAD_REQUEST, "确认文本与课程名不一致，未执行重置"));
        mvc.perform(post(BASE + "/data-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5,\"confirmText\":\"软件\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("确认文本与课程名不一致，未执行重置"));

        when(dataResetService.resetCourse(5L, "软件工程"))
                .thenThrow(new BusinessException(ResultCode.FORBIDDEN, "生产环境已禁用学习数据重置"));
        mvc.perform(post(BASE + "/data-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5,\"confirmText\":\"软件工程\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("生产环境已禁用学习数据重置"));
    }

    // ───────────────────────── 路由鉴权 ─────────────────────────

    @Test
    @DisplayName("教师 / 学生访问任一接口 → 403；未登录 → 401；service 不被调用")
    void nonAdmins_rejectedOnEveryEndpoint() throws Exception {
        List<RequestBuilder> requests = List.of(
                get(BASE + "/configs"),
                put(BASE + "/configs/wenjin.ai.model").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"m\"}"),
                delete(BASE + "/configs/wenjin.ai.model"),
                post(BASE + "/configs/ai-test").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"text\"}"),
                post(BASE + "/data-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5,\"confirmText\":\"软件工程\"}"));

        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
        for (RequestBuilder request : requests) {
            mvc.perform(request).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
        }
        CurrentUser.set(new CurrentPrincipal(3L, UserRole.STUDENT));
        for (RequestBuilder request : requests) {
            mvc.perform(request).andExpect(status().isForbidden());
        }
        CurrentUser.clear();
        for (RequestBuilder request : requests) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }

        verifyNoInteractions(configService, aiConnectionTester, dataResetService);
        // 已登录越权的写请求照记失败审计（匿名不记）
        verify(auditLogMapper, atLeastOnce()).insert(any(AuditLog.class));
    }

    private static AdminConfigItemVO item(String key, String type) {
        AdminConfigItemVO v = new AdminConfigItemVO();
        v.setKey(key);
        v.setLabel("标签");
        v.setDescription("说明");
        v.setType(type);
        return v;
    }
}
