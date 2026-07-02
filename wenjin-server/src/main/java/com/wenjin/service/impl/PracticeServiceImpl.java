package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.PracticeSession;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.PracticeSessionMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.PracticeService;
import com.wenjin.support.QuestionStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 节点练习服务实现（M1 练习闭环 T3）。
 *
 * <h3>组卷策略</h3>
 * <ol>
 *   <li>从 {@code question_node} 取该节点 weight=1 关联题，过滤 status=APPROVED；</li>
 *   <li>排除该学生 {@code recencyDays} 天内在任意场景答过的题（查 {@code answer_record}）；</li>
 *   <li>不足 {@code effectiveSize} 时放宽到 weight=2（同样过滤 status + 近期）；</li>
 *   <li>≥1 题即可开会话；0 题时抛 {@link BusinessException} 提示题库不足；</li>
 *   <li>轻度难度分层：优先覆盖难度 2–4 各至少一题（难度排序取样简化版）。</li>
 * </ol>
 *
 * <h3>注意</h3>
 * <ul>
 *   <li>Controller/鉴权由 T7 实现，此层不调用 AccessGuard。</li>
 *   <li>UpdateWrapper 字符串列名写法与 PathServiceImpl 保持一致（Lambda 缓存单测会报错）。</li>
 * </ul>
 */
@Service
public class PracticeServiceImpl implements PracticeService {

    /** practice_session.status：进行中 */
    private static final int SESSION_IN_PROGRESS = 0;

    private final QuestionNodeMapper questionNodeMapper;
    private final QuestionMapper questionMapper;
    private final AnswerRecordMapper answerRecordMapper;
    private final KgNodeMapper kgNodeMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final PracticeSessionMapper practiceSessionMapper;

    /** 每会话默认题数（配置项 wenjin.practice.size）。 */
    @Value("${wenjin.practice.size:5}")
    private int defaultSize;

    /** 每会话最大题数上限（配置项 wenjin.practice.max-size）。 */
    @Value("${wenjin.practice.max-size:10}")
    private int maxSize;

    /** 组卷近期排除窗口（天数，配置项 wenjin.practice.recency-days）。 */
    @Value("${wenjin.practice.recency-days:7}")
    private int recencyDays;

    public PracticeServiceImpl(QuestionNodeMapper questionNodeMapper,
                               QuestionMapper questionMapper,
                               AnswerRecordMapper answerRecordMapper,
                               KgNodeMapper kgNodeMapper,
                               QuestionOptionMapper questionOptionMapper,
                               PracticeSessionMapper practiceSessionMapper) {
        this.questionNodeMapper = questionNodeMapper;
        this.questionMapper = questionMapper;
        this.answerRecordMapper = answerRecordMapper;
        this.kgNodeMapper = kgNodeMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.practiceSessionMapper = practiceSessionMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PracticeStartVO start(Long studentId, Long courseId, Long nodeId, Integer size) {
        // ── 1. 解析 size：null 取默认值，超上限夹紧 ─────────────────────────
        int effectiveSize = (size == null) ? defaultSize : Math.min(size, maxSize);

        // ── 2. 校验节点存在 ───────────────────────────────────────────────────
        KgNode node = kgNodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识点不存在：nodeId=" + nodeId);
        }

        // ── 3. 查询该学生近期（recencyDays 天内）已答题 ID 集合 ──────────────
        //    任意场景（scene 不限）均算在排除窗口内
        LocalDateTime cutoff = LocalDateTime.now().minusDays(recencyDays);
        List<AnswerRecord> recentRecords = answerRecordMapper.selectList(
                new LambdaQueryWrapper<AnswerRecord>()
                        .eq(AnswerRecord::getStudentId, studentId)
                        .ge(AnswerRecord::getAnsweredAt, cutoff));
        Set<Long> recentQuestionIds = recentRecords.stream()
                .map(AnswerRecord::getQuestionId)
                .collect(Collectors.toSet());

        // ── 4. 取 weight=1 已审核题池（扣除近期已答） ────────────────────────
        List<Question> pool = fetchApprovedPool(nodeId, 1, recentQuestionIds);
        Set<Long> poolIds = pool.stream().map(Question::getId).collect(Collectors.toSet());

        // ── 5. 若 weight=1 不足，放宽到 weight=2 补充 ────────────────────────
        if (pool.size() < effectiveSize) {
            List<Question> w2Pool = fetchApprovedPool(nodeId, 2, recentQuestionIds);
            for (Question q : w2Pool) {
                if (poolIds.add(q.getId())) { // 去重（题可能同时有 weight=1 和 weight=2 链接）
                    pool.add(q);
                }
            }
        }

        // ── 6. 0 题可用 → 抛业务异常 ─────────────────────────────────────────
        if (pool.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "题库不足，该知识点暂无可用题目（已过滤未审核与近期已答）");
        }

        // ── 7. 轻度难度分层取样，最多 effectiveSize 道 ───────────────────────
        List<Question> selected = stratifyPick(pool, effectiveSize);

        // ── 8. 冻结 question_ids，创建 practice_session（status=0） ──────────
        String questionIds = selected.stream()
                .map(q -> String.valueOf(q.getId()))
                .collect(Collectors.joining(","));

        PracticeSession session = new PracticeSession();
        session.setStudentId(studentId);
        session.setCourseId(courseId);
        session.setNodeId(nodeId);
        session.setQuestionIds(questionIds);
        session.setStatus(SESSION_IN_PROGRESS);
        session.setCreatedAt(LocalDateTime.now());
        // submittedAt 留 null（未提交）
        practiceSessionMapper.insert(session);

        // ── 9. 构建脱敏 VO ────────────────────────────────────────────────────
        List<PaperQuestionVO> questionVOs = buildQuestionVOs(selected, node.getChapter());

        PracticeStartVO vo = new PracticeStartVO();
        vo.setSessionId(session.getId());
        PracticeStartVO.NodeRef nodeRef = new PracticeStartVO.NodeRef();
        nodeRef.setNodeId(node.getId());
        nodeRef.setNodeCode(node.getNodeCode());
        nodeRef.setName(node.getName());
        vo.setNode(nodeRef);
        vo.setQuestions(questionVOs);
        return vo;
    }

    // ── 内部方法 ─────────────────────────────────────────────────────────────

    /**
     * 查询该节点指定权重下的 APPROVED 题，排除近期已答。
     *
     * @param nodeId        知识点 ID
     * @param weight        权重（1=主, 2=次）
     * @param recentIds     近期已答题 ID 集合（用于排除）
     * @return 可用题列表（按 DB 返回顺序，downstream 会重排）
     */
    private List<Question> fetchApprovedPool(Long nodeId, int weight, Set<Long> recentIds) {
        // 查询 question_node 关联
        List<QuestionNode> links = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>()
                        .eq(QuestionNode::getNodeId, nodeId)
                        .eq(QuestionNode::getWeight, weight));
        if (links.isEmpty()) {
            return new ArrayList<>();
        }

        // 提取 questionId，查 question（DB 过滤 APPROVED）
        List<Long> questionIds = links.stream()
                .map(QuestionNode::getQuestionId)
                .collect(Collectors.toList());
        List<Question> approved = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .in(Question::getId, questionIds)
                        .eq(Question::getStatus, QuestionStatus.APPROVED));

        // 排除近期已答
        return approved.stream()
                .filter(q -> !recentIds.contains(q.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 轻度难度分层取样：优先覆盖难度 2–4 各至少一题，随后从剩余题中补足（同样优先 2–4 范围）。
     *
     * <p>"允许简化为难度排序取样"：先按"是否在 2–4 范围内"降序，再按难度升序，再按 id 升序排列，
     * 取前 {@code size} 道；首先尝试从 2、3、4 各取一道种子题以保证覆盖，再填充剩余。</p>
     *
     * @param pool 候选题池（保证 ≥ 1 道）
     * @param size 目标题数（已夹紧）
     */
    private List<Question> stratifyPick(List<Question> pool, int size) {
        if (pool.size() <= size) {
            return new ArrayList<>(pool);
        }

        List<Question> selected = new ArrayList<>(size);
        Set<Long> selectedIds = new HashSet<>();

        // 步骤 A：从难度 2、3、4 各取 id 最小的一道作为种子（保证覆盖 2–4 范围）
        for (int targetDiff : new int[]{2, 3, 4}) {
            if (selected.size() >= size) break;
            pool.stream()
                    .filter(q -> q.getDifficulty() != null && q.getDifficulty() == targetDiff)
                    .min(Comparator.comparingLong(Question::getId))
                    .ifPresent(q -> {
                        if (selectedIds.add(q.getId())) {
                            selected.add(q);
                        }
                    });
        }

        // 步骤 B：补充剩余配额，按"2–4 优先，其他次之；同段内难度升序，再 id 升序"
        if (selected.size() < size) {
            pool.stream()
                    .filter(q -> !selectedIds.contains(q.getId()))
                    .sorted(Comparator
                            .<Question, Integer>comparing(q -> inPreferredRange(q) ? 0 : 1)
                            .thenComparingInt(q -> q.getDifficulty() == null ? 999 : q.getDifficulty())
                            .thenComparingLong(Question::getId))
                    .forEach(q -> {
                        if (selected.size() < size) {
                            selected.add(q);
                            selectedIds.add(q.getId());
                        }
                    });
        }

        return selected;
    }

    /** 难度 2–4 为"首选"范围，供排序优先级使用。 */
    private boolean inPreferredRange(Question q) {
        return q.getDifficulty() != null && q.getDifficulty() >= 2 && q.getDifficulty() <= 4;
    }

    /**
     * 将已选题列表构造为脱敏 VO（复用 {@link PaperQuestionVO} 同款结构）。
     * 选项只暴露 key + text，绝不含 isCorrect / pointNodeCode / answer。
     *
     * @param questions 已选题（保持 stratifyPick 返回顺序，与 question_ids 冻结顺序一致）
     * @param chapter   练习节点的章节（统一设为该节点所属章节）
     */
    private List<PaperQuestionVO> buildQuestionVOs(List<Question> questions, String chapter) {
        if (questions.isEmpty()) {
            return List.of();
        }
        List<Long> ids = questions.stream().map(Question::getId).collect(Collectors.toList());

        // 批量查选项（一次 IN，避免 N+1）
        List<QuestionOption> allOpts = questionOptionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>()
                        .in(QuestionOption::getQuestionId, ids));
        Map<Long, List<QuestionOption>> optsByQuestion = allOpts.stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionId));

        return questions.stream()
                .map(q -> {
                    PaperQuestionVO vo = new PaperQuestionVO();
                    vo.setQuestionId(q.getId());
                    vo.setStem(q.getStem());
                    vo.setType(q.getType());
                    vo.setChapter(chapter); // 同节点下所有题共享节点 chapter

                    List<QuestionOption> opts = optsByQuestion.getOrDefault(q.getId(), List.of());
                    List<PaperQuestionVO.OptionVO> optVOs = opts.stream()
                            .sorted(Comparator.comparing(QuestionOption::getOptionKey))
                            .map(o -> {
                                PaperQuestionVO.OptionVO ov = new PaperQuestionVO.OptionVO();
                                ov.setKey(o.getOptionKey());
                                ov.setText(o.getOptionText());
                                // 绝不设置 isCorrect / pointNodeCode：脱敏保证
                                return ov;
                            })
                            .collect(Collectors.toList());
                    vo.setOptions(optVOs);
                    return vo;
                })
                .collect(Collectors.toList());
    }
}
