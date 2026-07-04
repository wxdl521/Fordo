package com.wenjin.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 组卷抽取共享组件（M2 T4 从 {@code PracticeServiceImpl} 纯重构抽出）。
 *
 * <p>封装 M1 练习闭环确立的组卷策略，供 {@code PracticeService}（节点练习）与
 * {@code AdaptiveDiagnosticService}（自适应诊断探针）两处复用，避免判分/抽题逻辑重复。
 *
 * <h3>抽题级联（与 M1 行为逐字一致）</h3>
 * <ol>
 *   <li>weight=1 主关联、status=APPROVED、排除近期已答（{@code recentQuestionIds}）；</li>
 *   <li>不足 {@code size} 时放宽到 weight=2 补充（去重，同样过滤 status+近期）；</li>
 *   <li>weight=1∪2 经近期排除后仍为 0（I3a 复练场景）→ 末档放宽允许重复近期题；</li>
 *   <li>轻度难度分层取样（{@link #stratifyPick}）取前 {@code size} 道。</li>
 * </ol>
 *
 * <h3>空态语义（调用方负责）</h3>
 * <p>本组件不抛业务异常：题库为空时返回空列表，由调用方决定处理
 * （Practice 抛"题库不足"、Adaptive 视为该节点不可实测）。
 *
 * <h3>{@code accept} 谓词（跨域差异点）</h3>
 * <p>Practice 传 {@code q -> true}（不额外过滤，行为零变化）；
 * Adaptive 传"仅客观题"谓词（探针不含简答，简报裁决 2）。谓词在 APPROVED 过滤之后、
 * 近期排除同层应用，因此对末档放宽同样生效。
 *
 * <p>无 Spring 注解，纯 {@code new} 可用（服务层持有 mapper，构造时注入）。
 */
public class QuestionPicker {

    private final QuestionNodeMapper questionNodeMapper;
    private final QuestionMapper questionMapper;

    public QuestionPicker(QuestionNodeMapper questionNodeMapper, QuestionMapper questionMapper) {
        this.questionNodeMapper = questionNodeMapper;
        this.questionMapper = questionMapper;
    }

    /**
     * 为单个知识点抽题（组卷）。
     *
     * @param nodeId            目标知识点
     * @param size              目标题数（调用方已夹紧）
     * @param recentQuestionIds 近期已答题 ID 集合（用于排除，调用方按 recency 窗口预算好）
     * @param accept            额外过滤谓词（Practice=恒真；Adaptive=仅客观题）
     * @return 已选题列表（保持抽题顺序，可能为空——由调用方处理空态）
     */
    public List<Question> pick(Long nodeId, int size, Set<Long> recentQuestionIds,
                               Predicate<Question> accept) {
        // ── 1. weight=1 主关联池（扣除近期已答） ────────────────────────────
        List<Question> pool = fetchApprovedPool(nodeId, 1, recentQuestionIds, accept);
        Set<Long> poolIds = pool.stream().map(Question::getId).collect(Collectors.toSet());

        // ── 2. 不足 size 时放宽到 weight=2 补充 ─────────────────────────────
        if (pool.size() < size) {
            List<Question> w2Pool = fetchApprovedPool(nodeId, 2, recentQuestionIds, accept);
            for (Question q : w2Pool) {
                if (poolIds.add(q.getId())) { // 去重（题可能同时挂 weight=1 和 weight=2）
                    pool.add(q);
                }
            }
        }

        // ── 3. 近期排除后为 0（I3a 复练兜底）→ 末档允许重复近期题 ─────────────
        if (pool.isEmpty()) {
            List<Question> fullW1 = fetchApprovedPool(nodeId, 1, Set.of(), accept);
            Set<Long> fullIds = fullW1.stream().map(Question::getId).collect(Collectors.toSet());
            if (fullW1.size() < size) {
                List<Question> fullW2 = fetchApprovedPool(nodeId, 2, Set.of(), accept);
                for (Question q : fullW2) {
                    if (fullIds.add(q.getId())) {
                        fullW1.add(q);
                    }
                }
            }
            pool = fullW1;
        }

        // ── 4. 空池：交由调用方处理空态 ─────────────────────────────────────
        if (pool.isEmpty()) {
            return new ArrayList<>();
        }

        // ── 5. 轻度难度分层取样，最多 size 道 ───────────────────────────────
        return stratifyPick(pool, size);
    }

    /**
     * 查询该节点指定权重下的 APPROVED 题，排除近期已答，再应用 {@code accept} 谓词。
     *
     * @param nodeId    知识点 ID
     * @param weight    权重（1=主, 2=次）
     * @param recentIds 近期已答题 ID 集合（用于排除）
     * @param accept    额外过滤谓词
     * @return 可用题列表（按 DB 返回顺序，downstream 会重排）
     */
    private List<Question> fetchApprovedPool(Long nodeId, int weight, Set<Long> recentIds,
                                             Predicate<Question> accept) {
        List<QuestionNode> links = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>()
                        .eq(QuestionNode::getNodeId, nodeId)
                        .eq(QuestionNode::getWeight, weight));
        if (links.isEmpty()) {
            return new ArrayList<>(); // 早返回：无链接则不触碰 questionMapper
        }

        List<Long> questionIds = links.stream()
                .map(QuestionNode::getQuestionId)
                .collect(Collectors.toList());
        List<Question> approved = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .in(Question::getId, questionIds)
                        .eq(Question::getStatus, QuestionStatus.APPROVED));

        return approved.stream()
                .filter(q -> !recentIds.contains(q.getId()))
                .filter(accept)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 轻度难度分层取样：优先覆盖难度 2–4 各至少一题，随后从剩余题补足（同样优先 2–4）。
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
}
