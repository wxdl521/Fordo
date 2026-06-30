package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenjin.dto.QuestionReviewRequest;
import com.wenjin.dto.ReviewAllRequest;
import com.wenjin.dto.TeacherQuestionPageVO;
import com.wenjin.dto.TeacherQuestionVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.support.QuestionStatus;

import java.util.Comparator;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.TeacherQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeacherQuestionServiceImpl implements TeacherQuestionService {

    private static final int MAIN_POINT_WEIGHT = 1;

    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final KgNodeMapper nodeMapper;

    @Override
    public TeacherQuestionPageVO list(Long courseId, Integer status, String nodeCode, String conf, int page, int size) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }

        // C4: Validate pagination parameters
        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 20;  // Limit max 100

        // I8: Validate status parameter
        if (status != null && (status < 0 || status > 2)) {
            throw new IllegalArgumentException("status 无效（0=待审/1=通过/2=驳回）: " + status);
        }

        // T6: 使用抽取的私有方法解析 nodeCode 过滤 id 集合
        Set<Long> nodeFilterQids = resolveNodeFilterQids(courseId, nodeCode);

        // nodeCode 指定但无匹配主考点题目 → 早退空页（外部行为不变）
        if (nodeCode != null && !nodeCode.trim().isEmpty() && nodeFilterQids != null && nodeFilterQids.isEmpty()) {
            return emptyPage(page, size, courseId);
        }

        // T6: 使用抽取的私有方法构建筛选 wrapper
        LambdaQueryWrapper<Question> w = buildFilter(courseId, status, conf, nodeFilterQids);

        List<Question> filtered = questionMapper.selectList(w);

        // Sort by confidence desc (nulls last)
        // C2: TODO: 生产环境改用数据库层面的 ORDER BY confidence DESC LIMIT offset, size
        filtered.sort(Comparator.comparing(
                Question::getConfidence,
                Comparator.nullsLast(Comparator.reverseOrder())));

        // Memory pagination
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        List<Question> pageList = filtered.subList(from, to);

        // C1: Batch load to avoid N+1 queries
        List<TeacherQuestionVO> items;
        if (pageList.isEmpty()) {
            items = Collections.emptyList();
        } else {
            items = batchToVO(pageList);
        }

        // Counts: query ALL questions for this course (not just filtered subset)
        LambdaQueryWrapper<Question> countW = new LambdaQueryWrapper<>();
        countW.eq(Question::getCourseId, courseId);
        List<Question> allForCourse = questionMapper.selectList(countW);
        TeacherQuestionPageVO.Counts counts = computeCounts(allForCourse);

        TeacherQuestionPageVO result = new TeacherQuestionPageVO();
        result.setTotal((long) filtered.size());
        result.setPage(page);
        result.setSize(size);
        result.setItems(items);
        result.setCounts(counts);

        return result;
    }

    @Override
    public int review(Long courseId, QuestionReviewRequest req) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }
        if (req == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        if (req.getIds() == null || req.getIds().isEmpty()) {
            throw new IllegalArgumentException("ids 不能为空");
        }
        if (req.getAction() == null || req.getAction().trim().isEmpty()) {
            throw new IllegalArgumentException("action 不能为空");
        }

        int target;
        switch (req.getAction().toLowerCase()) {
            case "pass":
                target = QuestionStatus.APPROVED;
                break;
            case "reject":
                target = QuestionStatus.REJECTED;
                break;
            default:
                throw new IllegalArgumentException("action 无效: " + req.getAction() + " (必须是 pass 或 reject)");
        }

        // Use UpdateWrapper with string column name to avoid Lambda.set() reflection issue in unit tests
        UpdateWrapper<Question> uw = new UpdateWrapper<>();
        uw.set("status", target)
                .eq("course_id", courseId)
                .in("id", req.getIds());

        return questionMapper.update(null, uw);
    }

    /**
     * T6: 服务端全量审批，select+update 同一事务，避免前端分页漂移漏审/重审问题。
     * 复用私有方法 resolveNodeFilterQids/buildFilter 与 list() 共享筛选逻辑（DRY）。
     */
    @Override
    @Transactional
    public int reviewAll(Long courseId, ReviewAllRequest req) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }
        if (req == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        if (req.getAction() == null || req.getAction().trim().isEmpty()) {
            throw new IllegalArgumentException("action 不能为空");
        }

        int target;
        switch (req.getAction().toLowerCase()) {
            case "pass":
                target = QuestionStatus.APPROVED;
                break;
            case "reject":
                target = QuestionStatus.REJECTED;
                break;
            default:
                throw new IllegalArgumentException("action 无效: " + req.getAction() + " (必须是 pass 或 reject)");
        }

        // T6: 复用私有方法解析 nodeCode 过滤 id 集合
        Set<Long> nodeFilterQids = resolveNodeFilterQids(courseId, req.getNodeCode());

        // nodeCode 指定但无匹配主考点题目 → 直接返回 0，不发 update
        if (req.getNodeCode() != null && !req.getNodeCode().trim().isEmpty()
                && nodeFilterQids != null && nodeFilterQids.isEmpty()) {
            return 0;
        }

        // T6: 复用私有方法构建筛选 wrapper，取出待更新 id 列表
        LambdaQueryWrapper<Question> w = buildFilter(courseId, req.getStatus(), req.getConf(), nodeFilterQids);
        List<Question> toUpdate = questionMapper.selectList(w);
        if (toUpdate.isEmpty()) {
            return 0;
        }

        List<Long> ids = toUpdate.stream().map(Question::getId).collect(Collectors.toList());

        // 沿用 review() 的字符串列名 UpdateWrapper，规避 Lambda.set() 反射问题
        UpdateWrapper<Question> uw = new UpdateWrapper<>();
        uw.set("status", target)
                .eq("course_id", courseId)
                .in("id", ids);

        return questionMapper.update(null, uw);
    }

    /**
     * T6: 抽取私有方法 — 解析 nodeCode 过滤 id 集合。
     * nodeCode 为空 → 返回 null（不过滤）；
     * 非空 → 查 KgNode → 查主考点 QuestionNode → 返回 questionId 集合（可能为空集，表示无匹配）。
     * list() 与 reviewAll() 共同调用，消除重复的 nodeCode 解析逻辑。
     */
    private Set<Long> resolveNodeFilterQids(Long courseId, String nodeCode) {
        if (nodeCode == null || nodeCode.trim().isEmpty()) {
            return null; // null 表示不按 nodeCode 过滤
        }

        KgNode kn = nodeMapper.selectOne(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .eq(KgNode::getNodeCode, nodeCode)
        );

        Set<Long> qids = new HashSet<>();
        if (kn != null) {
            List<QuestionNode> qns = questionNodeMapper.selectList(
                    new LambdaQueryWrapper<QuestionNode>()
                            .eq(QuestionNode::getNodeId, kn.getId())
                            .eq(QuestionNode::getWeight, MAIN_POINT_WEIGHT)
            );
            for (QuestionNode qn : qns) {
                qids.add(qn.getQuestionId());
            }
        }
        // 返回集合（可能为空集，表示 nodeCode 有效但无对应主考点题目）
        return qids;
    }

    /**
     * T6: 抽取私有方法 — 构建题目筛选 LambdaQueryWrapper。
     * courseId 必须；status 非 null 时 eq；nodeFilterQids 非 null 时 in；conf 非空时 applyConf。
     * list() 与 reviewAll() 共同调用，消除重复的 wrapper 构建逻辑。
     */
    private LambdaQueryWrapper<Question> buildFilter(Long courseId, Integer status, String conf,
                                                      Set<Long> nodeFilterQids) {
        LambdaQueryWrapper<Question> w = new LambdaQueryWrapper<>();
        w.eq(Question::getCourseId, courseId);

        if (status != null) {
            w.eq(Question::getStatus, status);
        }

        if (nodeFilterQids != null) {
            w.in(Question::getId, nodeFilterQids);
        }

        if (conf != null && !conf.trim().isEmpty()) {
            applyConf(w, conf);
        }

        return w;
    }

    private void applyConf(LambdaQueryWrapper<Question> w, String conf) {
        switch (conf.toLowerCase()) {
            case "ge85":
                w.ge(Question::getConfidence, 85);
                break;
            case "mid":
                w.ge(Question::getConfidence, 70).lt(Question::getConfidence, 85);
                break;
            case "lt70":
                w.lt(Question::getConfidence, 70);
                break;
            default:
                // I6: Throw exception for invalid conf values
                throw new IllegalArgumentException("conf 参数无效，仅支持: ge85/mid/lt70，当前值: " + conf);
        }
    }

    /**
     * C1: Batch load options, question_nodes, and kg_nodes to avoid N+1 queries.
     * Instead of querying 2-3 times per question, we:
     * 1. Query all options for all questions once
     * 2. Query all question_nodes for all questions once
     * 3. Query all kg_nodes for collected nodeIds once
     * 4. Build maps and use them in toVO
     */
    private List<TeacherQuestionVO> batchToVO(List<Question> questions) {
        List<Long> qids = questions.stream().map(Question::getId).collect(Collectors.toList());

        // Batch load options
        Map<Long, List<QuestionOption>> optionsMap = new HashMap<>();
        List<QuestionOption> allOptions = questionOptionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>()
                        .in(QuestionOption::getQuestionId, qids)
                        .orderByAsc(QuestionOption::getQuestionId)
                        .orderByAsc(QuestionOption::getOptionKey)
        );
        for (QuestionOption opt : allOptions) {
            optionsMap.computeIfAbsent(opt.getQuestionId(), k -> new ArrayList<>()).add(opt);
        }

        // Batch load question_nodes (main points only)
        Map<Long, List<QuestionNode>> qnMap = new HashMap<>();
        List<QuestionNode> allQns = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>()
                        .in(QuestionNode::getQuestionId, qids)
                        .eq(QuestionNode::getWeight, MAIN_POINT_WEIGHT)
        );
        for (QuestionNode qn : allQns) {
            qnMap.computeIfAbsent(qn.getQuestionId(), k -> new ArrayList<>()).add(qn);
        }

        // Batch load kg_nodes
        Set<Long> nodeIds = allQns.stream().map(QuestionNode::getNodeId).collect(Collectors.toSet());
        Map<Long, KgNode> nodeMap = new HashMap<>();
        if (!nodeIds.isEmpty()) {
            List<KgNode> nodes = nodeMapper.selectList(
                    new LambdaQueryWrapper<KgNode>().in(KgNode::getId, nodeIds)
            );
            for (KgNode n : nodes) {
                nodeMap.put(n.getId(), n);
            }
        }

        // Convert to VOs using cached data
        return questions.stream()
                .map(q -> toVO(q, optionsMap.getOrDefault(q.getId(), Collections.emptyList()),
                        qnMap.getOrDefault(q.getId(), Collections.emptyList()), nodeMap))
                .collect(Collectors.toList());
    }

    private TeacherQuestionVO toVO(Question q, List<QuestionOption> options,
                                    List<QuestionNode> qns, Map<Long, KgNode> nodeMap) {
        TeacherQuestionVO vo = new TeacherQuestionVO();
        vo.setId(q.getId());
        vo.setStem(q.getStem());
        vo.setType(q.getType());
        vo.setDifficulty(q.getDifficulty());
        vo.setConfidence(q.getConfidence());
        vo.setStatus(q.getStatus());
        vo.setSource(q.getSource());
        vo.setCreatedAt(q.getCreatedAt());

        // Use pre-loaded options
        vo.setOptions(options.stream()
                .map(opt -> {
                    TeacherQuestionVO.OptionVO optVO = new TeacherQuestionVO.OptionVO();
                    optVO.setKey(opt.getOptionKey());
                    optVO.setText(opt.getOptionText());
                    optVO.setCorrect(opt.getIsCorrect() != null && opt.getIsCorrect() == 1);
                    optVO.setPointNodeCode(opt.getPointNodeCode());
                    return optVO;
                })
                .collect(Collectors.toList()));

        // Use pre-loaded main node
        if (!qns.isEmpty()) {
            Long nodeId = qns.get(0).getNodeId();
            KgNode node = nodeMap.get(nodeId);
            if (node != null) {
                vo.setMainNodeCode(node.getNodeCode());
                vo.setMainNodeName(node.getName());
            }
        }

        return vo;
    }

    /**
     * C3: Compute counts from the already-loaded list instead of re-querying database.
     */
    private TeacherQuestionPageVO.Counts computeCounts(List<Question> all) {
        TeacherQuestionPageVO.Counts counts = new TeacherQuestionPageVO.Counts();
        counts.setPending(all.stream().filter(q -> q.getStatus() == QuestionStatus.PENDING).count());
        counts.setPassed(all.stream().filter(q -> q.getStatus() == QuestionStatus.APPROVED).count());
        counts.setRejected(all.stream().filter(q -> q.getStatus() == QuestionStatus.REJECTED).count());
        return counts;
    }

    private TeacherQuestionPageVO emptyPage(int page, int size, Long courseId) {
        TeacherQuestionPageVO result = new TeacherQuestionPageVO();
        result.setTotal(0L);
        result.setPage(page);
        result.setSize(size);
        result.setItems(Collections.emptyList());

        // Still calculate counts for the whole course
        LambdaQueryWrapper<Question> w = new LambdaQueryWrapper<>();
        w.eq(Question::getCourseId, courseId);
        List<Question> all = questionMapper.selectList(w);

        result.setCounts(computeCounts(all));

        return result;
    }
}
