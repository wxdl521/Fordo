package com.wenjin.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.config.CurrentUser;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.EdgeCreateRequest;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.GraphService;
import com.wenjin.service.TeacherGraphService;
import com.wenjin.support.PrerequisiteCycleDetector;
import com.wenjin.support.QuestionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图谱差量导入 — 主键身份保持与软归档集成测试（Testcontainers MySQL）。
 */
@SpringBootTest
@ActiveProfiles("it")
class GraphIdentityPreservationIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-test-secret-not-for-prod");
        registry.add("wenjin.auth.secret", () -> "it-test-secret-not-for-prod");
        registry.add("wenjin.features.graph-reconcile-enabled", () -> "true");
    }

    @Autowired private GraphService graphService;
    @Autowired private TeacherGraphService teacherGraphService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private KgNodeMapper kgNodeMapper;
    @Autowired private KgEdgeMapper kgEdgeMapper;
    @Autowired private QuestionMapper questionMapper;
    @Autowired private QuestionNodeMapper questionNodeMapper;
    @Autowired private StudentMasteryMapper studentMasteryMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long teacherId;
    private long studentId;
    private long courseId;
    private String courseCode;

    @BeforeEach
    void seed() {
        CurrentUser.clear();
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{
                "answer_record", "student_mastery", "mastery_snapshot",
                "diagnostic_session_question", "diagnostic_session",
                "question_option", "question_node", "question",
                "kg_edge", "kg_node", "student_course", "course", "sys_user"
        }) {
            try {
                jdbcTemplate.execute("DELETE FROM `" + table + "`");
            } catch (Exception ignored) {
                // table may not exist
            }
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

        SysUser teacher = new SysUser();
        teacher.setUsername("t-graph-id");
        teacher.setPassword("x");
        teacher.setRole(UserRole.TEACHER.code());
        teacher.setStatus(1);
        sysUserMapper.insert(teacher);
        teacherId = teacher.getId();

        SysUser student = new SysUser();
        student.setUsername("s-graph-id");
        student.setPassword("x");
        student.setRole(UserRole.STUDENT.code());
        student.setStatus(1);
        sysUserMapper.insert(student);
        studentId = student.getId();

        Course course = new Course();
        courseCode = "GRAPHID01";
        course.setCode(courseCode);
        course.setName("身份保持课");
        course.setTeacherId(teacherId);
        course.setStatus(1);
        courseMapper.insert(course);
        courseId = course.getId();

        CurrentUser.set(new CurrentPrincipal(teacherId, UserRole.TEACHER));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("同 node_code 重导入保留 kg_node.id；question_node/mastery 仍指向同一 id")
    void reimportPreservesNodeIdAndDownstreamFks() {
        graphService.importGraph(courseCode, request(
                List.of(n("KT01", "节点一"), n("KT02", "节点二")),
                List.of(e("KT01", "KT02", "前置"))));

        KgNode kt01 = findActiveNode("KT01");
        KgNode kt02 = findActiveNode("KT02");
        long id01 = kt01.getId();
        long id02 = kt02.getId();

        Question q = new Question();
        q.setCourseId(courseId);
        q.setStem("题干");
        q.setStemHash(com.wenjin.support.StemHash.sha256Hex("题干"));
        q.setType(1);
        q.setDifficulty(2);
        q.setAnswer("A");
        q.setStatus(QuestionStatus.APPROVED);
        questionMapper.insert(q);

        QuestionNode qn = new QuestionNode();
        qn.setQuestionId(q.getId());
        qn.setNodeId(id01);
        qn.setWeight(1);
        questionNodeMapper.insert(qn);

        StudentMastery sm = new StudentMastery();
        sm.setStudentId(studentId);
        sm.setCourseId(courseId);
        sm.setNodeId(id01);
        sm.setMasteryLevel(2);
        sm.setMasteryScore(new BigDecimal("80.00"));
        studentMasteryMapper.insert(sm);

        // 重导入：改名、去掉 KT02、加 KT03
        graphService.importGraph(courseCode, request(
                List.of(n("KT01", "节点一改名"), n("KT03", "节点三")),
                List.of()));

        KgNode kt01Again = kgNodeMapper.selectById(id01);
        assertThat(kt01Again).isNotNull();
        assertThat(kt01Again.getId()).isEqualTo(id01);
        assertThat(kt01Again.getName()).isEqualTo("节点一改名");
        assertThat(kt01Again.getArchivedAt()).isNull();

        KgNode kt02Archived = kgNodeMapper.selectById(id02);
        assertThat(kt02Archived).isNotNull();
        assertThat(kt02Archived.getArchivedAt()).isNotNull();

        // 下游外键仍指向原 id
        QuestionNode qnAgain = questionNodeMapper.selectById(qn.getId());
        assertThat(qnAgain.getNodeId()).isEqualTo(id01);
        StudentMastery smAgain = studentMasteryMapper.selectById(sm.getId());
        assertThat(smAgain.getNodeId()).isEqualTo(id01);

        // 新节点插入
        KgNode kt03 = findActiveNode("KT03");
        assertThat(kt03.getId()).isNotEqualTo(id01).isNotEqualTo(id02);

        // 学生视图不含归档节点
        long activeCount = kgNodeMapper.selectCount(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt));
        assertThat(activeCount).isEqualTo(2);
    }

    @Test
    @DisplayName("幂等重导入：相同载荷不改节点主键")
    void idempotentReimportKeepsIds() {
        graphService.importGraph(courseCode, request(
                List.of(n("KT01", "A"), n("KT02", "B")),
                List.of(e("KT01", "KT02", "前置"))));

        Long id1 = findActiveNode("KT01").getId();
        Long id2 = findActiveNode("KT02").getId();
        KgEdge edge = kgEdgeMapper.selectOne(new LambdaQueryWrapper<KgEdge>()
                .eq(KgEdge::getCourseId, courseId)
                .isNull(KgEdge::getArchivedAt));
        Long edgeId = edge.getId();

        graphService.importGraph(courseCode, request(
                List.of(n("KT01", "A"), n("KT02", "B")),
                List.of(e("KT01", "KT02", "前置"))));

        assertThat(findActiveNode("KT01").getId()).isEqualTo(id1);
        assertThat(findActiveNode("KT02").getId()).isEqualTo(id2);
        KgEdge edgeAgain = kgEdgeMapper.selectById(edgeId);
        assertThat(edgeAgain.getArchivedAt()).isNull();
        assertThat(edgeAgain.getFromNodeId()).isEqualTo(id1);
        assertThat(edgeAgain.getToNodeId()).isEqualTo(id2);

        long nodeRows = kgNodeMapper.selectCount(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));
        assertThat(nodeRows).isEqualTo(2);
        long edgeRows = kgEdgeMapper.selectCount(
                new LambdaQueryWrapper<KgEdge>().eq(KgEdge::getCourseId, courseId));
        assertThat(edgeRows).isEqualTo(1);
    }

    @Test
    @DisplayName("移除后再导回：同一 node_code 复活并保留原 id")
    void archiveThenRestoreKeepsId() {
        graphService.importGraph(courseCode, request(
                List.of(n("KT01", "A"), n("KT02", "B")),
                List.of()));
        long id02 = findActiveNode("KT02").getId();

        graphService.importGraph(courseCode, request(List.of(n("KT01", "A")), List.of()));
        assertThat(kgNodeMapper.selectById(id02).getArchivedAt()).isNotNull();

        graphService.importGraph(courseCode, request(
                List.of(n("KT01", "A"), n("KT02", "B-restored")),
                List.of()));

        KgNode restored = kgNodeMapper.selectById(id02);
        assertThat(restored.getArchivedAt()).isNull();
        assertThat(restored.getName()).isEqualTo("B-restored");
        assertThat(findActiveNode("KT02").getId()).isEqualTo(id02);
    }

    @Test
    @DisplayName("并发全量导入最终等于其中一个完整 payload，不能混合")
    void concurrentFullImportsDoNotMixSnapshots() throws Exception {
        GraphImportRequest left = request(
                List.of(n("A1", "A-one"), n("A2", "A-two")),
                List.of(e("A1", "A2", "前置")));
        GraphImportRequest right = request(
                List.of(n("B1", "B-one"), n("B2", "B-two")),
                List.of(e("B1", "B2", "前置")));

        race(() -> graphService.importGraph(courseCode, left),
                () -> graphService.importGraph(courseCode, right));

        List<KgNode> activeNodes = kgNodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .isNull(KgNode::getArchivedAt));
        List<String> activeCodes = activeNodes.stream().map(KgNode::getNodeCode).sorted().toList();
        assertThat(activeCodes).isIn(List.of("A1", "A2"), List.of("B1", "B2"));

        List<KgEdge> activeEdges = kgEdgeMapper.selectList(new LambdaQueryWrapper<KgEdge>()
                .eq(KgEdge::getCourseId, courseId)
                .isNull(KgEdge::getArchivedAt));
        assertThat(activeEdges).hasSize(1);
        List<Long> activeIds = activeNodes.stream().map(KgNode::getId).toList();
        assertThat(activeIds).contains(activeEdges.get(0).getFromNodeId(), activeEdges.get(0).getToNodeId());
    }

    @Test
    @DisplayName("并发创建反向前置边最多一条成功且最终无环")
    void concurrentReversePrerequisitesAllowAtMostOneAndRemainAcyclic() throws Exception {
        graphService.importGraph(courseCode,
                request(List.of(n("A", "A"), n("B", "B")), List.of()));

        List<Boolean> outcomes = race(
                () -> tryCreatePrerequisite("A", "B"),
                () -> tryCreatePrerequisite("B", "A"));

        assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        List<KgNode> nodes = kgNodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .isNull(KgNode::getArchivedAt));
        Map<Long, String> codesById = new HashMap<>();
        nodes.forEach(node -> codesById.put(node.getId(), node.getNodeCode()));
        List<KgEdge> edges = kgEdgeMapper.selectList(new LambdaQueryWrapper<KgEdge>()
                .eq(KgEdge::getCourseId, courseId)
                .eq(KgEdge::getRelationType, 1)
                .isNull(KgEdge::getArchivedAt));
        Map<String, List<String>> adjacency = new HashMap<>();
        for (KgEdge edge : edges) {
            adjacency.computeIfAbsent(codesById.get(edge.getFromNodeId()), ignored -> new ArrayList<>())
                    .add(codesById.get(edge.getToNodeId()));
        }
        assertThat(edges).hasSize(1);
        assertThat(PrerequisiteCycleDetector.detect(adjacency)).isNull();
    }

    private boolean tryCreatePrerequisite(String source, String target) {
        EdgeCreateRequest request = new EdgeCreateRequest();
        request.setSourceNodeCode(source);
        request.setTargetNodeCode(target);
        request.setRelationType(1);
        try {
            teacherGraphService.createEdge(courseId, request);
            return true;
        } catch (BusinessException ex) {
            assertThat(ex.getCode()).isEqualTo(ResultCode.CONFLICT.getCode());
            return false;
        }
    }

    private <T> List<T> race(Callable<T> left, Callable<T> right) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<T> leftFuture = executor.submit(raceTask(start, left));
            Future<T> rightFuture = executor.submit(raceTask(start, right));
            return List.of(leftFuture.get(30, TimeUnit.SECONDS), rightFuture.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> Callable<T> raceTask(CyclicBarrier start, Callable<T> task) {
        return () -> {
            CurrentUser.set(new CurrentPrincipal(teacherId, UserRole.TEACHER));
            try {
                start.await(10, TimeUnit.SECONDS);
                return task.call();
            } finally {
                CurrentUser.clear();
            }
        };
    }

    private KgNode findActiveNode(String code) {
        return kgNodeMapper.selectOne(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .eq(KgNode::getNodeCode, code)
                .isNull(KgNode::getArchivedAt));
    }

    private static GraphImportRequest request(List<GraphImportRequest.NodeItem> nodes,
                                              List<GraphImportRequest.EdgeItem> edges) {
        GraphImportRequest r = new GraphImportRequest();
        r.setNodes(new ArrayList<>(nodes));
        r.setEdges(new ArrayList<>(edges));
        return r;
    }

    private static GraphImportRequest.NodeItem n(String id, String name) {
        GraphImportRequest.NodeItem item = new GraphImportRequest.NodeItem();
        item.setId(id);
        item.setName(name);
        item.setChapter("章");
        item.setDifficulty(2);
        item.setIsKey(false);
        return item;
    }

    private static GraphImportRequest.EdgeItem e(String src, String tgt, String type) {
        GraphImportRequest.EdgeItem item = new GraphImportRequest.EdgeItem();
        item.setSource(src);
        item.setTarget(tgt);
        item.setType(type);
        return item;
    }
}
