package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PaperVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.DiagnosticService;
import com.wenjin.support.QuestionStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 入口诊断服务实现（T6：PRD 6.1 分层抽样组卷）。
 * <p>
 * 核心流程：
 *   1. 取课程全部 APPROVED 题；
 *   2. 查每题主知识点（weight=1），从图谱节点拿 chapter / isKey；
 *   3. 按章节分组，分层抽样：min-1 保覆盖 + 比例分配余量；
 *   4. 组内排序：isKey desc, id asc（确定性，不引入随机）；
 *   5. 构造 PaperVO，选项只暴露 key + text，绝不含答案/正误信息。
 */
@Service
public class DiagnosticServiceImpl implements DiagnosticService {

    /** 主知识点权重值 */
    private static final int WEIGHT_MAIN = 1;
    /** chapter 缺失时的兜底值 */
    private static final String CHAPTER_UNKNOWN = "未分类";

    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final KgNodeMapper kgNodeMapper;

    /** 试卷目标题数，可通过配置覆盖；未配置时默认 25。 */
    @Value("${wenjin.diagnostic.paper-size:25}")
    private int paperSize;

    public DiagnosticServiceImpl(QuestionMapper questionMapper,
                                 QuestionOptionMapper questionOptionMapper,
                                 QuestionNodeMapper questionNodeMapper,
                                 KgNodeMapper kgNodeMapper) {
        this.questionMapper = questionMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.questionNodeMapper = questionNodeMapper;
        this.kgNodeMapper = kgNodeMapper;
    }

    @Override
    public PaperVO composePaper(Long courseId) {
        // ── 0. 配置合法性前置校验 ─────────────────────────────────────
        if (paperSize <= 0) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "诊断试卷题数配置不合法: " + paperSize);
        }

        // ── 1. 取所有 APPROVED 题 ──────────────────────────────────────
        List<Question> approved = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .eq(Question::getCourseId, courseId)
                        .eq(Question::getStatus, QuestionStatus.APPROVED));

        PaperVO paper = new PaperVO();
        paper.setCourseId(courseId);

        if (approved.isEmpty()) {
            paper.setTotal(0);
            paper.setQuestions(List.of());
            return paper;
        }

        // ── 2. 收集题目 ID，查主知识点（weight=1） ────────────────────
        List<Long> approvedIds = approved.stream().map(Question::getId).collect(Collectors.toList());

        List<QuestionNode> mainNodes = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>()
                        .in(QuestionNode::getQuestionId, approvedIds)
                        .eq(QuestionNode::getWeight, WEIGHT_MAIN));

        // questionId → 第一条 weight=1 行的 nodeId
        Map<Long, Long> questionToNodeId = new HashMap<>();
        for (QuestionNode qn : mainNodes) {
            questionToNodeId.putIfAbsent(qn.getQuestionId(), qn.getNodeId());
        }

        // ── 3. 取课程所有图谱节点，构建 nodeId → KgNode ──────────────
        List<KgNode> kgNodes = kgNodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));

        Map<Long, KgNode> nodeMap = new HashMap<>();
        for (KgNode n : kgNodes) {
            nodeMap.put(n.getId(), n);
        }

        // 为每道题推导 chapter / isKey
        Map<Long, String> questionChapter = new HashMap<>();
        Map<Long, Boolean> questionIsKey = new HashMap<>();
        for (Question q : approved) {
            Long nodeId = questionToNodeId.get(q.getId());
            KgNode node = nodeId == null ? null : nodeMap.get(nodeId);
            String chapter = (node != null && node.getChapter() != null)
                    ? node.getChapter() : CHAPTER_UNKNOWN;
            boolean isKey = node != null && node.getIsKey() != null && node.getIsKey() == 1;
            questionChapter.put(q.getId(), chapter);
            questionIsKey.put(q.getId(), isKey);
        }

        // ── 4. 按章节分组（LinkedHashMap 保留首见顺序） ───────────────
        LinkedHashMap<String, List<Question>> byChapter = new LinkedHashMap<>();
        for (Question q : approved) {
            String ch = questionChapter.get(q.getId());
            byChapter.computeIfAbsent(ch, k -> new ArrayList<>()).add(q);
        }

        // ── 5. 计算 target ────────────────────────────────────────────
        int target = Math.min(paperSize, approved.size());
        int numChapters = byChapter.size();

        // ── 6. 分层配额（stratified, proportional, min-1 per chapter） ─
        //   若章数 > target，只从前 target 章各取 1 题
        List<String> chapterOrder = new ArrayList<>(byChapter.keySet());
        Map<String, Integer> quota = new LinkedHashMap<>();

        if (numChapters >= target) {
            // 章多于 target：每章各取 1 题，只用前 target 章
            for (int i = 0; i < target; i++) {
                quota.put(chapterOrder.get(i), 1);
            }
        } else {
            // 先保底每章 1 题
            for (String ch : chapterOrder) {
                quota.put(ch, 1);
            }
            // 剩余配额按比例分配（最大余数法）
            int remaining = target - numChapters;
            int total = approved.size();

            // 计算各章理想份额（先下取整）
            Map<String, Double> idealExtra = new LinkedHashMap<>();
            Map<String, Integer> floorExtra = new LinkedHashMap<>();
            int floorSum = 0;
            for (String ch : chapterOrder) {
                int chSize = byChapter.get(ch).size();
                double ideal = (double) chSize / total * remaining;
                idealExtra.put(ch, ideal);
                int floor = (int) ideal;
                floorExtra.put(ch, floor);
                floorSum += floor;
            }

            // 最大余数：把 (remaining - floorSum) 个额外配额分给余数最大的章
            int extra = remaining - floorSum;
            List<String> byRemainder = new ArrayList<>(chapterOrder);
            byRemainder.sort((a, b) -> {
                double ra = idealExtra.get(a) - floorExtra.get(a);
                double rb = idealExtra.get(b) - floorExtra.get(b);
                return Double.compare(rb, ra); // 降序
            });
            for (int i = 0; i < extra; i++) {
                String ch = byRemainder.get(i);
                floorExtra.put(ch, floorExtra.get(ch) + 1);
            }

            // 合并基础 1 + 比例分配，不超过该章题数
            for (String ch : chapterOrder) {
                int cap = byChapter.get(ch).size();
                int assigned = 1 + floorExtra.get(ch);
                quota.put(ch, Math.min(assigned, cap));
            }
        }

        // ── 7. 各章内按 isKey desc, id asc 取配额 ────────────────────
        List<Question> selected = new ArrayList<>();
        // 记录已选 id，用于后续补全
        java.util.Set<Long> selectedIdSet = new java.util.LinkedHashSet<>();

        for (String ch : (numChapters >= target ? chapterOrder.subList(0, target) : chapterOrder)) {
            List<Question> pool = new ArrayList<>(byChapter.get(ch));
            // 排序：isKey desc, id asc
            pool.sort(Comparator
                    .comparingInt((Question q) -> questionIsKey.get(q.getId()) ? 0 : 1)
                    .thenComparingLong(Question::getId));
            int q = quota.getOrDefault(ch, 0);
            for (int i = 0; i < q && i < pool.size(); i++) {
                selected.add(pool.get(i));
                selectedIdSet.add(pool.get(i).getId());
            }
        }

        // ── 8. 若因 cap 导致不足 target，从未选题中补全 ──────────────
        if (selected.size() < target) {
            List<Question> unselected = approved.stream()
                    .filter(q -> !selectedIdSet.contains(q.getId()))
                    .sorted(Comparator
                            .comparingInt((Question q) -> questionIsKey.get(q.getId()) ? 0 : 1)
                            .thenComparingLong(Question::getId))
                    .collect(Collectors.toList());
            int need = target - selected.size();
            for (int i = 0; i < need && i < unselected.size(); i++) {
                selected.add(unselected.get(i));
            }
        }

        // ── 9. 构造 PaperVO（选项只暴露 key + text，不含答案/正误） ───
        // 批量查询所有已选题的选项（一次 IN 查询，避免 N+1）
        List<Long> selectedIds = selected.stream().map(Question::getId).toList();
        List<QuestionOption> allOpts = questionOptionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>()
                        .in(QuestionOption::getQuestionId, selectedIds));
        Map<Long, List<QuestionOption>> optsByQuestion = allOpts.stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionId));

        List<PaperQuestionVO> questionVOs = new ArrayList<>(selected.size());
        for (Question q : selected) {
            PaperQuestionVO vo = new PaperQuestionVO();
            vo.setQuestionId(q.getId());
            vo.setStem(q.getStem());
            vo.setChapter(questionChapter.get(q.getId()));
            vo.setType(q.getType());

            // 从批量查询结果中取选项，仅暴露 key + text
            List<QuestionOption> opts = optsByQuestion.getOrDefault(q.getId(), List.of());
            List<PaperQuestionVO.OptionVO> optVOs = opts.stream()
                    .sorted(Comparator.comparing(QuestionOption::getOptionKey))
                    .map(o -> {
                        PaperQuestionVO.OptionVO ov = new PaperQuestionVO.OptionVO();
                        ov.setKey(o.getOptionKey());
                        ov.setText(o.getOptionText());
                        // 绝不设置 isCorrect / pointNodeCode / answer
                        return ov;
                    })
                    .collect(Collectors.toList());
            vo.setOptions(optVOs);

            questionVOs.add(vo);
        }

        paper.setQuestions(questionVOs);
        paper.setTotal(questionVOs.size());
        return paper;
    }
}
