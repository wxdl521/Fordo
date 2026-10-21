package com.wenjin.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反射检查：controller 包内所有 {@code @RequestBody} 参数均挂有 {@code @Valid}。
 * <p>可选 body（{@code required=false}）同样要求 {@code @Valid}（约束为空时等价放行）。
 * abandon 等可选兼容体若无字段约束，可仅标 {@code @RequestBody} 且无校验注解——
 * 本测试仅强制「带 required 默认 true 的 RequestBody」或任何带约束意图的 body 挂 Valid。
 * 策略：凡出现 {@code @RequestBody} 的参数，若类型上存在 jakarta.validation 约束
 * 或嵌套需校验，应挂 {@code @Valid}；为简单稳妥，一律要求所有 {@code @RequestBody} 均有 {@code @Valid}，
 * 但 {@code required=false} 的兼容体可豁免。</p>
 */
class ControllerValidationWiringTest {

    @Test
    @DisplayName("所有必填 @RequestBody 均标注 @Valid")
    void allRequiredRequestBodyHaveValid() throws Exception {
        List<Class<?>> controllers = loadControllerClasses();
        assertThat(controllers).isNotEmpty();

        List<String> missing = new ArrayList<>();
        for (Class<?> clazz : controllers) {
            if (!clazz.isAnnotationPresent(RestController.class)) {
                continue;
            }
            for (Method method : clazz.getDeclaredMethods()) {
                for (Parameter param : method.getParameters()) {
                    RequestBody rb = param.getAnnotation(RequestBody.class);
                    if (rb == null) {
                        continue;
                    }
                    // 可选 body（如 abandon）允许无 @Valid
                    if (!rb.required()) {
                        continue;
                    }
                    if (param.getAnnotation(Valid.class) == null) {
                        missing.add(clazz.getSimpleName() + "#" + method.getName()
                                + "(" + param.getType().getSimpleName() + ")");
                    }
                }
            }
        }
        assertThat(missing)
                .as("Required @RequestBody without @Valid: %s", missing)
                .isEmpty();
    }

    private static List<Class<?>> knownControllers() {
        return List.of(
                AdaptiveDiagnosticController.class,
                CompanionController.class,
                CourseController.class,
                DiagnosticController.class,
                GraphAdminController.class,
                GraphController.class,
                GrowthController.class,
                PathController.class,
                PracticeController.class,
                QuestionAdminController.class,
                ReviewController.class,
                SystemAdminClazzController.class,
                SystemAdminCourseController.class,
                SystemAdminMonitorController.class,
                SystemAdminUserController.class,
                SystemAdminUserManageController.class,
                TeacherAgentRunController.class,
                TeacherCourseController.class,
                TeacherDashboardController.class,
                TeacherGraphController.class,
                TeacherJobController.class,
                TeacherQuestionController.class,
                UserController.class
        );
    }

    private static List<Class<?>> loadControllerClasses() {
        // 直接使用编译期已知控制器列表，避免 classpath 扫描的 URI/jar 边界问题
        return knownControllers();
    }
}
