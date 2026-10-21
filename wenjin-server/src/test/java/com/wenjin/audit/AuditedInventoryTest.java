package com.wenjin.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住"哪些控制器方法进审计日志"的清单：新增/删除 {@link Audited} 标注必须同步改这里，
 * 避免悄悄漏记关键操作，或把只读 GET 误标进审计。
 */
class AuditedInventoryTest {

    private static final List<String> EXPECTED = List.of(
            // 用户管理
            "SystemAdminUserController#changeRole=USER_ROLE:USER",
            "SystemAdminUserManageController#create=USER_CREATE:USER",
            "SystemAdminUserManageController#update=USER_UPDATE:USER",
            "SystemAdminUserManageController#changeStatus=USER_STATUS:USER",
            "SystemAdminUserManageController#resetPassword=USER_RESET_PASSWORD:USER",
            "SystemAdminUserManageController#importUsers=USER_IMPORT:USER",
            // 课程
            "TeacherCourseController#create=COURSE_CREATE:COURSE",
            "TeacherCourseController#delete=COURSE_DELETE:COURSE",
            "TeacherCourseController#restore=COURSE_RESTORE:COURSE",
            "TeacherCourseController#setStatus=COURSE_STATUS:COURSE",
            // 图谱
            "GraphAdminController#importGraph=GRAPH_IMPORT:GRAPH",
            "GraphAdminController#importGraphFromExcel=GRAPH_IMPORT:GRAPH",
            "GraphAdminController#commit=GRAPH_EXTRACT_COMMIT:GRAPH",
            "TeacherGraphController#createNode=GRAPH_NODE_CREATE:GRAPH",
            "TeacherGraphController#updateNode=GRAPH_NODE_UPDATE:GRAPH",
            "TeacherGraphController#deleteNode=GRAPH_NODE_DELETE:GRAPH",
            "TeacherGraphController#createEdge=GRAPH_EDGE_CREATE:GRAPH",
            "TeacherGraphController#acceptEdge=GRAPH_EDGE_ACCEPT:GRAPH",
            "TeacherGraphController#rejectEdge=GRAPH_EDGE_REJECT:GRAPH",
            // 题库
            "TeacherQuestionController#review=QUESTION_REVIEW:QUESTION",
            "TeacherQuestionController#reviewAll=QUESTION_REVIEW_ALL:QUESTION",
            "QuestionAdminController#generate=QUESTION_GENERATE:QUESTION",
            "QuestionAdminController#importJson=QUESTION_IMPORT:QUESTION",
            "QuestionAdminController#importExcel=QUESTION_IMPORT:QUESTION",
            // RAG 映射审核
            "TeacherRagController#approve=RAG_MAPPING_APPROVE:RAG",
            "TeacherRagController#reject=RAG_MAPPING_REJECT:RAG",
            "TeacherRagController#batchApprove=RAG_MAPPING_BATCH_APPROVE:RAG");

    @Test
    @DisplayName("被 @Audited 标注的控制器方法恰好是约定清单")
    void auditedMethods_matchAgreedInventory() throws Exception {
        List<String> actual = new ArrayList<>();
        for (Method m : auditedMethods()) {
            Audited a = m.getAnnotation(Audited.class);
            actual.add(m.getDeclaringClass().getSimpleName() + "#" + m.getName()
                    + "=" + a.action() + ":" + a.target());
        }

        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    @DisplayName("审计只标写操作：每个被标注的方法都映射到 POST/PUT/PATCH/DELETE，且 action/target 非空")
    void auditedMethods_areMutatingAndWellFormed() throws Exception {
        for (Method m : auditedMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            assertThat(mapping).as(m + " 应有请求映射").isNotNull();
            assertThat(Arrays.asList(mapping.method()))
                    .as(m + " 应是写操作")
                    .isNotEmpty()
                    .doesNotContain(RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS);
            Audited a = m.getAnnotation(Audited.class);
            assertThat(a.action()).as(m + " action").matches("[A-Z][A-Z_]*");
            assertThat(a.target()).as(m + " target").isNotBlank();
        }
    }

    private static List<Method> auditedMethods() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Method> result = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.wenjin.controller")) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Audited.class)) {
                    result.add(m);
                }
            }
        }
        return result;
    }
}
