package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.QuestionAiClient;
import com.wenjin.ai.dto.AiAnnotation;
import com.wenjin.ai.dto.AiDistractor;
import com.wenjin.ai.dto.AiQuestion;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AnnotateItemResult;
import com.wenjin.dto.AnnotateRequest;
import com.wenjin.dto.GenerateResult;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.GraphQueryService;
import com.wenjin.service.QuestionService;
import com.wenjin.service.QuestionWriteService;
import com.wenjin.support.StemHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 题目服务编排：读图 / AI 均在事务外；短写委托 {@link QuestionWriteService}。
 * <p>出题流水线：归属校验 → 白名单 → AI（最多 2 轮，每轮请求 remaining）→ 指纹去重 → 短事务落库。
 */
@Service
public class QuestionServiceImpl implements QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionServiceImpl.class);

    private static final int WHITELIST_DEPTH = 2;
    /** 出题最多轮数：初始 + 一次重试 */
    private static final int MAX_ROUNDS = 2;
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 20;

    private final GraphQueryService graphQueryService;
    private final QuestionAiClient aiClient;
    private final KgNodeMapper nodeMapper;
    private final QuestionMapper questionMapper;
    private final CourseAccessService courseAccessService;
    private final QuestionWriteService questionWriteService;

    public QuestionServiceImpl(GraphQueryService graphQueryService,
                               QuestionAiClient aiClient,
                               KgNodeMapper nodeMapper,
                               QuestionMapper questionMapper,
                               CourseAccessService courseAccessService,
                               QuestionWriteService questionWriteService) {
        this.graphQueryService = graphQueryService;
        this.aiClient = aiClient;
        this.nodeMapper = nodeMapper;
        this.questionMapper = questionMapper;
        this.courseAccessService = courseAccessService;
        this.questionWriteService = questionWriteService;
    }

    @Override
    public GenerateResult generate(Long courseId, String nodeCode, int count) {
        courseAccessService.assertManageCourse(courseId);

        // count 非法：在 AI 之前 400（控制器亦有 @Min/@Max；服务层防直调）
        if (count < MIN_COUNT || count > MAX_COUNT) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "出题数量须在 " + MIN_COUNT + "–" + MAX_COUNT + " 之间");
        }
        int target = Math.max(1, Math.min(count, 20));

        // 1. 白名单（codes）：目标 + 1–2 层前置（短读，无事务）
        Set<String> whitelist = graphQueryService.whitelistOf(courseId, nodeCode, WHITELIST_DEPTH);

        List<KgNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt));
        Map<String, String> codeToName = new HashMap<>();
        Map<String, String> codeToChapter = new HashMap<>();
        for (KgNode n : nodes) {
            codeToName.put(n.getNodeCode(), n.getName());
            codeToChapter.put(n.getNodeCode(), n.getChapter());
        }
        String targetName = codeToName.get(nodeCode);
        if (targetName == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "目标知识点不存在: " + nodeCode);
        }
        String targetChapter = codeToChapter.get(nodeCode);
        List<String[]> aiWhitelist = buildAiWhitelist(whitelist, codeToName);

        // 2. AI 出题（事务外）：最多 2 轮，每轮只请求 remaining
        List<AiQuestion> valid = new ArrayList<>();
        int dropped = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            int remaining = target - valid.size();
            if (remaining <= 0) {
                break;
            }
            List<AiQuestion> batch =
                    aiClient.generate(nodeCode, targetName, targetChapter, remaining, aiWhitelist);
            if (batch != null) {
                for (AiQuestion q : batch) {
                    if (isValid(q, whitelist)) {
                        if (valid.size() < target) {
                            valid.add(q);
                        }
                    } else {
                        dropped++;
                    }
                }
            }
            if (valid.size() >= target) {
                break;
            }
        }

        // 3. 指纹去重 + 短事务落库（每题一次短写；总量 ≤ target）
        Map<String, Long> codeToId = graphQueryService.codeToId(courseId);
        GenerateResult result = new GenerateResult();
        result.setDropped(dropped);
        Set<String> seenHashes = new HashSet<>();
        for (AiQuestion q : valid) {
            if (result.getGenerated() >= target) {
                break;
            }
            String stem = q.getStem().trim();
            String hash = StemHash.sha256Hex(stem);
            if (seenHashes.contains(hash) || existsStemHash(courseId, hash)) {
                result.setDuplicated(result.getDuplicated() + 1);
                continue;
            }
            seenHashes.add(hash);
            try {
                Long questionId = questionWriteService.persistGenerated(courseId, stem, q, codeToId);
                result.getQuestionIds().add(questionId);
                result.setGenerated(result.getGenerated() + 1);
            } catch (BusinessException ex) {
                if (ex.getCode() == ResultCode.CONFLICT.getCode()) {
                    result.setDuplicated(result.getDuplicated() + 1);
                } else {
                    throw ex;
                }
            }
        }

        result.setMessage(String.format(
                "生成 %d 道，丢弃 %d 道，去重 %d 道",
                result.getGenerated(), result.getDropped(), result.getDuplicated()));
        log.info("出题完成 nodeCode={} count={} target={} -> {}",
                nodeCode, count, target, result.getMessage());
        return result;
    }

    @Override
    public List<AnnotateItemResult> annotate(Long courseId, AnnotateRequest req) {
        courseAccessService.assertManageCourse(courseId);
        List<AnnotateItemResult> results = new ArrayList<>();
        if (req == null || req.getItems() == null || req.getItems().isEmpty()) {
            return results;
        }

        // 短读：白名单 + code 映射
        Set<String> whitelist = graphQueryService.allNodeCodes(courseId);
        Map<String, String> codeToName = codeToNameMap(courseId);
        List<String[]> aiWhitelist = buildAiWhitelist(whitelist, codeToName);
        Map<String, Long> codeToId = graphQueryService.codeToId(courseId);

        for (AnnotateRequest.Item item : req.getItems()) {
            results.add(annotateOne(courseId, item, whitelist, aiWhitelist, codeToId));
        }
        return results;
    }

    /** 标注单题：AI 在事务外；合法则短写落库。 */
    private AnnotateItemResult annotateOne(Long courseId, AnnotateRequest.Item item,
            Set<String> whitelist, List<String[]> aiWhitelist, Map<String, Long> codeToId) {
        String stem = item.getStem() == null ? null : item.getStem().trim();

        AnnotateItemResult result = new AnnotateItemResult();
        result.setStem(stem);
        result.setPersisted(false);

        List<String[]> aiOptions = new ArrayList<>();
        if (item.getOptions() != null) {
            for (AnnotateRequest.Option o : item.getOptions()) {
                aiOptions.add(new String[]{o.getKey(), o.getText()});
            }
        }

        AiAnnotation ann = aiClient.annotate(stem, aiOptions, aiWhitelist);
        if (ann == null) {
            result.setReason("AI 未返回标注结果");
            return result;
        }
        result.setSubPoints(ann.getSubPoints());
        result.setReason(ann.getReason());

        if (ann.getMainPoint() == null) {
            return result;
        }

        if (!annotationInWhitelist(ann, whitelist)) {
            result.setReason("标注考点超出课程图谱白名单");
            return result;
        }

        try {
            questionWriteService.persistAnnotated(courseId, stem, item, ann, codeToId);
            result.setMainPoint(ann.getMainPoint());
            result.setPersisted(true);
        } catch (BusinessException ex) {
            if (ex.getCode() == ResultCode.CONFLICT.getCode()) {
                result.setReason("题目题干已存在（指纹冲突）");
            } else {
                throw ex;
            }
        }
        return result;
    }

    private boolean annotationInWhitelist(AiAnnotation ann, Set<String> whitelist) {
        if (!whitelist.contains(ann.getMainPoint())) {
            return false;
        }
        if (ann.getSubPoints() != null) {
            for (String sub : ann.getSubPoints()) {
                if (!whitelist.contains(sub)) {
                    return false;
                }
            }
        }
        if (ann.getDistractors() != null) {
            for (AiDistractor d : ann.getDistractors()) {
                String pc = d.getPointNodeCode();
                if (StringUtils.hasText(pc) && !whitelist.contains(pc)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Map<String, String> codeToNameMap(Long courseId) {
        List<KgNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt));
        Map<String, String> codeToName = new HashMap<>();
        for (KgNode n : nodes) {
            codeToName.put(n.getNodeCode(), n.getName());
        }
        return codeToName;
    }

    private List<String[]> buildAiWhitelist(Set<String> codes, Map<String, String> codeToName) {
        List<String[]> aiWhitelist = new ArrayList<>();
        for (String code : codes) {
            aiWhitelist.add(new String[]{code, codeToName.getOrDefault(code, code)});
        }
        return aiWhitelist;
    }

    private boolean isValid(AiQuestion q, Set<String> whitelist) {
        if (q == null || !StringUtils.hasText(q.getStem()) || q.getOptions() == null || q.getOptions().isEmpty()) {
            return false;
        }
        int correctCount = 0;
        for (AiDistractor opt : q.getOptions()) {
            if (Boolean.TRUE.equals(opt.getCorrect())) {
                correctCount++;
            }
        }
        if (correctCount != 1) {
            return false;
        }
        if (!whitelist.contains(q.getMainPoint())) {
            return false;
        }
        if (q.getSubPoints() != null) {
            for (String sub : q.getSubPoints()) {
                if (!whitelist.contains(sub)) {
                    return false;
                }
            }
        }
        for (AiDistractor opt : q.getOptions()) {
            String pc = opt.getPointNodeCode();
            if (StringUtils.hasText(pc) && !whitelist.contains(pc)) {
                return false;
            }
        }
        return true;
    }

    /** 同课程下该题干指纹是否已存在。 */
    private boolean existsStemHash(Long courseId, String stemHash) {
        return !questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getCourseId, courseId)
                .eq(Question::getStemHash, stemHash)).isEmpty();
    }
}
