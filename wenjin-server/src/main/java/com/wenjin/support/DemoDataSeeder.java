package com.wenjin.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PaperVO;
import com.wenjin.dto.SubmitRequest;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.service.CourseService;
import com.wenjin.service.DiagnosticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示数据播种器（仅本地演示用）。
 * <p>
 * 通过 {@code wenjin.seed.enabled=true} 显式开启，默认关闭——<b>绝不在测试/生产默认运行</b>。
 * 走真实 {@link DiagnosticService#submit} 管线生成学情：answer_record + student_mastery +
 * mastery_snapshot 全部由判分/EWMA 真实算出（经得起答辩追问），而非手写 INSERT。
 * <p>
 * 三个差异化人设：
 * <ul>
 *   <li><b>A 基础薄弱</b>：大量答错 → 掌握度整体偏低、根因落在很上游、路径长；</li>
 *   <li><b>B 中间卡顿</b>：前置基本 OK，但故意选中映射到某前置点 Y 的干扰项 → 触发"错误多指向 Y"；</li>
 *   <li><b>C 接近掌握</b>：绝大多数答对，仅剩 1~2 个薄弱点 → 地图大面积变绿、路径短。</li>
 * </ul>
 * 每个学生跑两轮 submit（第二轮整体更准），让 mastery_snapshot 形成上升序列以填充成长曲线。
 * 幂等：已有 answer_record 的学生跳过（不重复灌数据）。
 */
@Component
@ConditionalOnProperty(name = "wenjin.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final int ROLE_STUDENT = 2;
    private static final int STATUS_ACTIVE = 1;
    private static final String DEMO_PASSWORD = "123456";

    private final SysUserMapper userMapper;
    private final CourseMapper courseMapper;
    private final KgNodeMapper nodeMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper optionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final AnswerRecordMapper answerRecordMapper;
    private final CourseService courseService;
    private final DiagnosticService diagnosticService;

    @Value("${wenjin.demo.course-id:1}")
    private Long courseId;

    public DemoDataSeeder(SysUserMapper userMapper,
                          CourseMapper courseMapper,
                          KgNodeMapper nodeMapper,
                          QuestionMapper questionMapper,
                          QuestionOptionMapper optionMapper,
                          QuestionNodeMapper questionNodeMapper,
                          AnswerRecordMapper answerRecordMapper,
                          CourseService courseService,
                          DiagnosticService diagnosticService) {
        this.userMapper = userMapper;
        this.courseMapper = courseMapper;
        this.nodeMapper = nodeMapper;
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
        this.questionNodeMapper = questionNodeMapper;
        this.answerRecordMapper = answerRecordMapper;
        this.courseService = courseService;
        this.diagnosticService = diagnosticService;
    }

    /** 人设标识。 */
    enum Persona { A_WEAK, B_STUCK, C_NEAR }

    /** 单题元信息（答案 + 干扰项考点 + 主考点 code + 难度），供构造作答模式用。 */
    record QMeta(Long questionId, String correctKey, int difficulty,
                 String mainNodeCode, List<String[]> wrongOptions) {
        // wrongOptions 每项为 [optionKey, pointNodeCode]（pointNodeCode 可空）
    }

    @Override
    public void run(String... args) {
        try {
            seed();
        } catch (Exception e) {
            // 播种失败绝不阻断应用启动
            log.warn("[seed] 演示数据播种失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }

    private void seed() {
        if (courseMapper.selectById(courseId) == null) {
            log.warn("[seed] 课程 {} 不存在，跳过播种。", courseId);
            return;
        }

        // 前置检查：图谱与题库（题库需带 point_node_code 干扰项才能让 B 的错因显形）
        long nodeCount = nodeMapper.selectCount(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));
        long approvedCount = questionMapper.selectCount(
                new LambdaQueryWrapper<Question>()
                        .eq(Question::getCourseId, courseId)
                        .eq(Question::getStatus, QuestionStatus.APPROVED));
        if (nodeCount == 0 || approvedCount == 0) {
            log.warn("[seed] 课程 {} 缺少图谱(节点 {}) 或已审核题库(题 {})，"
                    + "请先导入图谱与题库再开启 seed。", courseId, nodeCount, approvedCount);
            return;
        }

        // 组卷一次：三个学生共用同一套题面，只是作答模式不同
        PaperVO paper = diagnosticService.composePaper(courseId);
        if (paper.getQuestions() == null || paper.getQuestions().isEmpty()) {
            log.warn("[seed] 诊断卷为空，跳过播种。");
            return;
        }
        List<Long> qids = paper.getQuestions().stream()
                .map(PaperQuestionVO::getQuestionId).toList();

        Map<Long, QMeta> metaById = loadMeta(qids);

        // 选 B 的目标前置点 Y：被最多干扰项映射到的 point_node_code
        String targetPrereq = mostMappedPrereq(metaById, qids);
        if (targetPrereq == null) {
            log.warn("[seed] 题库无带 point_node_code 的干扰项，B 的「错误多指向 Y」将无法显形"
                    + "（请确认题库已含干扰项考点映射）。");
        }

        // 最难题（用于 C 仅留 1 个薄弱点）
        Long hardestQid = qids.stream()
                .max((a, b) -> Integer.compare(metaById.get(a).difficulty(), metaById.get(b).difficulty()))
                .orElse(qids.get(0));

        seedStudent("demo_a", "演示学生A·基础薄弱", Persona.A_WEAK, qids, metaById, targetPrereq, hardestQid);
        seedStudent("demo_b", "演示学生B·中间卡顿", Persona.B_STUCK, qids, metaById, targetPrereq, hardestQid);
        seedStudent("demo_c", "演示学生C·接近掌握", Persona.C_NEAR, qids, metaById, targetPrereq, hardestQid);

        log.info("[seed] 演示数据播种完成（课程 {}）。账号 demo_a / demo_b / demo_c，密码 {}。",
                courseId, DEMO_PASSWORD);
    }

    /** 创建/复用学生 → 选课 → 两轮真实 submit。已有作答记录则跳过（幂等）。 */
    private void seedStudent(String username, String realName, Persona persona,
                             List<Long> qids, Map<Long, QMeta> metaById,
                             String targetPrereq, Long hardestQid) {
        Long studentId = findOrCreateStudent(username, realName);
        courseService.enroll(studentId, courseId);

        long answered = answerRecordMapper.selectCount(
                new LambdaQueryWrapper<AnswerRecord>()
                        .eq(AnswerRecord::getStudentId, studentId)
                        .eq(AnswerRecord::getCourseId, courseId));
        if (answered > 0) {
            log.info("[seed] 学生 {}(id={}) 已有 {} 条作答记录，跳过（幂等）。", username, studentId, answered);
            return;
        }

        // 两轮：round 0 = 初测（更弱），round 1 = 复测（更准），形成上升的成长曲线
        for (int round = 0; round < 2; round++) {
            SubmitRequest req = new SubmitRequest();
            req.setStudentId(studentId);
            req.setCourseId(courseId);
            List<SubmitRequest.Answer> answers = new ArrayList<>(qids.size());
            for (int i = 0; i < qids.size(); i++) {
                QMeta m = metaById.get(qids.get(i));
                SubmitRequest.Answer a = new SubmitRequest.Answer();
                a.setQuestionId(m.questionId());
                a.setOptionKey(chooseAnswer(persona, round, i, m, targetPrereq, hardestQid));
                answers.add(a);
            }
            req.setAnswers(answers);
            diagnosticService.submit(req);
        }
        log.info("[seed] 学生 {}(id={}) 播种完成（人设 {}）。", username, studentId, persona);
    }

    /**
     * 按人设 + 轮次决定某题作答的选项标识。
     * 答对返回正确项 key；答错按"优先选映射到前置点的干扰项"挑一个错误 key。
     */
    String chooseAnswer(Persona persona, int round, int index, QMeta m,
                        String targetPrereq, Long hardestQid) {
        boolean correct;
        String preferPrereq = null; // 答错时优先选映射到此前置点的干扰项

        switch (persona) {
            case A_WEAK -> {
                // 初测大量错(约 80%)，复测仍偏弱(约 50%)：整体偏低，根因落很上游、路径长
                correct = (round == 0) ? (index % 5 == 0) : (index % 2 == 0);
            }
            case B_STUCK -> {
                boolean mapsToY = targetPrereq != null && hasWrongMappingTo(m, targetPrereq);
                boolean isYNode = targetPrereq != null && targetPrereq.equals(m.mainNodeCode());
                if (mapsToY) {
                    correct = false;
                    preferPrereq = targetPrereq; // 故意选中指向 Y 的干扰项 → "错误多指向 Y"
                } else if (isYNode) {
                    correct = false;             // 让前置点 Y 本身也薄弱，根因回溯到 Y
                } else {
                    // 其余题：初测一半对，复测全对 → 整体上升，但 Y 簇始终错
                    correct = (round == 1) || (index % 2 == 0);
                }
            }
            case C_NEAR -> {
                // 初测约 75% 对，复测仅最难一题错 → 大面积变绿、仅 1 个薄弱点
                correct = (round == 0) ? (index % 4 != 0) : !m.questionId().equals(hardestQid);
            }
            default -> correct = true;
        }

        if (correct && m.correctKey() != null) {
            return m.correctKey();
        }
        return pickWrong(m, preferPrereq);
    }

    /** 该题是否有"映射到 prereq 的干扰项"。 */
    private boolean hasWrongMappingTo(QMeta m, String prereq) {
        for (String[] w : m.wrongOptions()) {
            if (prereq.equals(w[1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 挑一个错误选项 key：
     *   · 若指定 preferPrereq，优先返回映射到它的干扰项；
     *   · 否则优先返回任意带 point_node_code 的干扰项（便于错因显形）；
     *   · 都没有则取第一个干扰项；无干扰项则退化为正确项。
     */
    String pickWrong(QMeta m, String preferPrereq) {
        if (preferPrereq != null) {
            for (String[] w : m.wrongOptions()) {
                if (preferPrereq.equals(w[1])) {
                    return w[0];
                }
            }
        }
        for (String[] w : m.wrongOptions()) {
            if (w[1] != null) {
                return w[0];
            }
        }
        if (!m.wrongOptions().isEmpty()) {
            return m.wrongOptions().get(0)[0];
        }
        return m.correctKey();
    }

    /** 被最多干扰项映射到的前置点 code（B 的目标 Y）；无则 null。 */
    String mostMappedPrereq(Map<Long, QMeta> metaById, List<Long> qids) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (Long qid : qids) {
            for (String[] w : metaById.get(qid).wrongOptions()) {
                if (w[1] != null) {
                    tally.merge(w[1], 1, Integer::sum);
                }
            }
        }
        return tally.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** 批量加载题面元信息（答案 / 干扰项考点 / 主考点 code / 难度）。 */
    private Map<Long, QMeta> loadMeta(List<Long> qids) {
        // 选项
        Map<Long, List<QuestionOption>> optsByQ = new HashMap<>();
        for (QuestionOption o : optionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>().in(QuestionOption::getQuestionId, qids))) {
            optsByQ.computeIfAbsent(o.getQuestionId(), k -> new ArrayList<>()).add(o);
        }
        // 主考点（weight=1）
        Map<Long, Long> qToNodeId = new HashMap<>();
        for (QuestionNode qn : questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>()
                        .in(QuestionNode::getQuestionId, qids)
                        .eq(QuestionNode::getWeight, 1))) {
            qToNodeId.putIfAbsent(qn.getQuestionId(), qn.getNodeId());
        }
        Map<Long, String> nodeIdToCode = new HashMap<>();
        if (!qToNodeId.isEmpty()) {
            for (KgNode n : nodeMapper.selectBatchIds(qToNodeId.values())) {
                nodeIdToCode.put(n.getId(), n.getNodeCode());
            }
        }
        // 难度
        Map<Long, Integer> difficultyByQ = new HashMap<>();
        for (Question q : questionMapper.selectBatchIds(qids)) {
            difficultyByQ.put(q.getId(), q.getDifficulty() == null ? 3 : q.getDifficulty());
        }

        Map<Long, QMeta> metaById = new HashMap<>();
        for (Long qid : qids) {
            String correctKey = null;
            List<String[]> wrong = new ArrayList<>();
            for (QuestionOption o : optsByQ.getOrDefault(qid, List.of())) {
                if (o.getIsCorrect() != null && o.getIsCorrect() == 1) {
                    if (correctKey == null) {
                        correctKey = o.getOptionKey();
                    }
                } else {
                    wrong.add(new String[]{o.getOptionKey(), o.getPointNodeCode()});
                }
            }
            String mainCode = nodeIdToCode.get(qToNodeId.get(qid));
            metaById.put(qid, new QMeta(qid, correctKey,
                    difficultyByQ.getOrDefault(qid, 3), mainCode, wrong));
        }
        return metaById;
    }

    /** 按 username 找学生，无则创建（role=student, status=active, 明文密码）。返回学生 id。 */
    private Long findOrCreateStudent(String username, String realName) {
        SysUser existing = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null) {
            return existing.getId();
        }
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(DEMO_PASSWORD);
        u.setRealName(realName);
        u.setRole(ROLE_STUDENT);
        u.setStatus(STATUS_ACTIVE);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(u);
        log.info("[seed] 创建演示学生 {}(id={})。", username, u.getId());
        return u.getId();
    }
}
