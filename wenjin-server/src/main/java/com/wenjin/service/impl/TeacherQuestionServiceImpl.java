package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenjin.dto.QuestionReviewRequest;
import com.wenjin.dto.QuestionStatusCount;
import com.wenjin.dto.ReviewAllRequest;
import com.wenjin.dto.TeacherQuestionPageVO;
import com.wenjin.dto.TeacherQuestionVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.support.QuestionStatus;

import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.security.CourseAccessService;
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
    /** reviewAll 分批 UPDATE 每批最大 id 数 */
    private static final int REVIEW_ALL_BATCH = 500;

    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final KgNodeMapper nodeMapper;
    private final CourseAccessService courseAccessService;

    @Override
    public TeacherQuestionPageVO list(Long courseId, Integer status, String nodeCode, String conf, int page, int size) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }
        courseAccessService.assertManageCourse(courseId);

        // page/size 边界由 Controller（@Min/@Max）拒绝；服务层不下沉静默修正
        if (status != null && (status < 0 || status > 2)) {
            throw new IllegalArgumentException("status 无效（0=待审/1=通过/2=驳回）: " + status);
        }
        String confKey = normalizeConf(conf);

        String nodeKey = blankToNull(nodeCode);
        int offset = (page - 1) * size;

        List<Question> pageList = questionMapper.selectTeacherPage(
                courseId, status, nodeKey, confKey, offset, size);
        long total = questionMapper.countTeacherFiltered(courseId, status, nodeKey, confKey);

        List<TeacherQuestionVO> items;
        if (pageList.isEmpty()) {
            items = Collections.emptyList();
        } else {
            items = batchToVO(pageList);
        }

        TeacherQuestionPageVO result = new TeacherQuestionPageVO();
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        result.setItems(items);
        result.setCounts(loadStatusCounts(courseId));
        return result;
    }

    @Override
    public int review(Long courseId, QuestionReviewRequest req) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }
        courseAccessService.assertManageCourse(courseId);
        if (req == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        if (req.getIds() == null || req.getIds().isEmpty()) {
            throw new IllegalArgumentException("ids 不能为空");
        }
        if (req.getAction() == null || req.getAction().trim().isEmpty()) {
            throw new IllegalArgumentException("action 不能为空");
        }
        // 批量：全部题目须存在且仅属于目标课程
        courseAccessService.assertQuestionsBelongToCourse(courseId, req.getIds());

        int target = resolveTargetStatus(req.getAction());

        // Use UpdateWrapper with string column name to avoid Lambda.set() reflection issue in unit tests
        UpdateWrapper<Question> uw = new UpdateWrapper<>();
        uw.set("status", target)
                .eq("course_id", courseId)
                .in("id", req.getIds());

        return questionMapper.update(null, uw);
    }

    /**
     * 服务端全量审批：按筛选谓词分批 UPDATE（每批最多 500），避免一次加载全课 id。
     * 当筛选含 status 且目标 status 不同时，每批更新后该批不再匹配，循环自然结束。
     * 当筛选不含 status（或 status 等于目标）时改用单次谓词 UPDATE，避免死循环。
     */
    @Override
    @Transactional
    public int reviewAll(Long courseId, ReviewAllRequest req) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }
        courseAccessService.assertManageCourse(courseId);
        if (req == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        if (req.getAction() == null || req.getAction().trim().isEmpty()) {
            throw new IllegalArgumentException("action 不能为空");
        }

        int target = resolveTargetStatus(req.getAction());
        String confKey = normalizeConf(req.getConf());
        String nodeKey = blankToNull(req.getNodeCode());
        Integer filterStatus = req.getStatus();

        // 筛选 status 为空，或与目标相同 → 单次谓词 UPDATE（避免分批死循环）
        if (filterStatus == null || filterStatus == target) {
            return questionMapper.updateStatusByFilter(courseId, target, filterStatus, nodeKey, confKey);
        }

        // 分批：每批最多 500，更新后因 status 变化不再命中筛选
        int total = 0;
        while (true) {
            List<Long> ids = questionMapper.selectIdsByFilter(
                    courseId, filterStatus, nodeKey, confKey, REVIEW_ALL_BATCH);
            if (ids == null || ids.isEmpty()) {
                break;
            }
            total += questionMapper.updateStatusByIds(courseId, ids, target);
            if (ids.size() < REVIEW_ALL_BATCH) {
                break;
            }
        }
        return total;
    }

    private int resolveTargetStatus(String action) {
        switch (action.toLowerCase()) {
            case "pass":
                return QuestionStatus.APPROVED;
            case "reject":
                return QuestionStatus.REJECTED;
            default:
                throw new IllegalArgumentException("action 无效: " + action + " (必须是 pass 或 reject)");
        }
    }

    /**
     * conf 校验并规范化；空串 → null；非法值抛错。
     * 分页边界不做静默修正（由 Controller 拒绝）。
     */
    private String normalizeConf(String conf) {
        if (conf == null || conf.trim().isEmpty()) {
            return null;
        }
        String key = conf.trim().toLowerCase();
        switch (key) {
            case "ge85":
            case "mid":
            case "lt70":
                return key;
            default:
                throw new IllegalArgumentException("conf 参数无效，仅支持: ge85/mid/lt70，当前值: " + conf);
        }
    }

    private static String blankToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }

    private TeacherQuestionPageVO.Counts loadStatusCounts(Long courseId) {
        List<QuestionStatusCount> rows = questionMapper.countGroupedByStatus(courseId);
        TeacherQuestionPageVO.Counts counts = new TeacherQuestionPageVO.Counts();
        long pending = 0, passed = 0, rejected = 0;
        if (rows != null) {
            for (QuestionStatusCount row : rows) {
                if (row.getStatus() == null || row.getCount() == null) {
                    continue;
                }
                switch (row.getStatus()) {
                    case QuestionStatus.PENDING -> pending = row.getCount();
                    case QuestionStatus.APPROVED -> passed = row.getCount();
                    case QuestionStatus.REJECTED -> rejected = row.getCount();
                    default -> { /* ignore unknown */ }
                }
            }
        }
        counts.setPending(pending);
        counts.setPassed(passed);
        counts.setRejected(rejected);
        return counts;
    }

    /**
     * Batch load options, question_nodes, and kg_nodes to avoid N+1 queries.
     */
    private List<TeacherQuestionVO> batchToVO(List<Question> questions) {
        List<Long> qids = questions.stream().map(Question::getId).collect(Collectors.toList());

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

        Map<Long, List<QuestionNode>> qnMap = new HashMap<>();
        List<QuestionNode> allQns = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>()
                        .in(QuestionNode::getQuestionId, qids)
                        .eq(QuestionNode::getWeight, MAIN_POINT_WEIGHT)
        );
        for (QuestionNode qn : allQns) {
            qnMap.computeIfAbsent(qn.getQuestionId(), k -> new ArrayList<>()).add(qn);
        }

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
}
