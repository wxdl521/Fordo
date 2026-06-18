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
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.GraphQueryService;
import com.wenjin.service.QuestionService;
import com.wenjin.support.QuestionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 题目服务实现。出题流水线（Prompt 2，AI 出题即标注）：
 *   取白名单 → 调 AI 出题 → 逐题代码侧校验（恰一个正确项、答案在选项内、考点⊆白名单）
 *   → 有效题不足整体重试一次 → 题干去重 → 落库 question/option/question_node。
 * 校验/去重在代码侧兜底，不信任 AI 输出。
 */
@Service
public class QuestionServiceImpl implements QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionServiceImpl.class);

    /** 默认难度（AI 未给时补） */
    private static final int DEFAULT_DIFFICULTY = 3;
    /** 白名单层数：目标 + 1–2 层前置 */
    private static final int WHITELIST_DEPTH = 2;
    /** 主考点权重 */
    private static final int WEIGHT_MAIN = 1;
    /** 次考点权重 */
    private static final int WEIGHT_SUB = 2;
    /** 出题最多轮数：初始 + 一次重试 */
    private static final int MAX_ROUNDS = 2;

    private final GraphQueryService graphQueryService;
    private final QuestionAiClient aiClient;
    private final KgNodeMapper nodeMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    public QuestionServiceImpl(GraphQueryService graphQueryService,
                               QuestionAiClient aiClient,
                               KgNodeMapper nodeMapper,
                               QuestionMapper questionMapper,
                               QuestionOptionMapper questionOptionMapper,
                               QuestionNodeMapper questionNodeMapper) {
        this.graphQueryService = graphQueryService;
        this.aiClient = aiClient;
        this.nodeMapper = nodeMapper;
        this.questionMapper = questionMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.questionNodeMapper = questionNodeMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GenerateResult generate(Long courseId, String nodeCode, int count) {

        // 1. 白名单（codes）：目标 + 1–2 层前置
        Set<String> whitelist = graphQueryService.whitelistOf(courseId, nodeCode, WHITELIST_DEPTH);

        // 2. 课程节点：建 code→name / code→chapter，并解析目标名称/章节
        List<KgNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));
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

        // 3. AI 白名单：每项 [code, name]
        List<String[]> aiWhitelist = buildAiWhitelist(whitelist, codeToName);

        // 4. 出题 + 校验（最多 2 轮：初始 + 一次重试），合并有效题
        List<AiQuestion> valid = new ArrayList<>();
        int dropped = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<AiQuestion> batch =
                    aiClient.generate(nodeCode, targetName, targetChapter, count, aiWhitelist);
            if (batch != null) {
                for (AiQuestion q : batch) {
                    if (isValid(q, whitelist)) {
                        valid.add(q);
                    } else {
                        dropped++;
                    }
                }
            }
            if (valid.size() >= count) {
                break; // 已满足，不再重试
            }
        }

        // 5. 去重落库
        Map<String, Long> codeToId = graphQueryService.codeToId(courseId);
        GenerateResult result = new GenerateResult();
        result.setDropped(dropped);
        Set<String> seenStems = new HashSet<>(); // 批内去重
        for (AiQuestion q : valid) {
            String stem = q.getStem().trim();
            if (seenStems.contains(stem) || existsStem(courseId, stem)) {
                result.setDuplicated(result.getDuplicated() + 1);
                continue;
            }
            seenStems.add(stem);
            Long questionId = persist(courseId, stem, q, codeToId);
            result.getQuestionIds().add(questionId);
            result.setGenerated(result.getGenerated() + 1);
        }

        result.setMessage(String.format(
                "生成 %d 道，丢弃 %d 道，去重 %d 道",
                result.getGenerated(), result.getDropped(), result.getDuplicated()));
        log.info("出题完成 nodeCode={} count={} -> {}", nodeCode, count, result.getMessage());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AnnotateItemResult> annotate(Long courseId, AnnotateRequest req) {
        List<AnnotateItemResult> results = new ArrayList<>();
        if (req == null || req.getItems() == null || req.getItems().isEmpty()) {
            return results;
        }

        // 1. 标注白名单（全图 codes）+ AI 白名单（每项 [code, name]）
        Set<String> whitelist = graphQueryService.allNodeCodes(courseId);
        Map<String, String> codeToName = codeToNameMap(courseId);
        List<String[]> aiWhitelist = buildAiWhitelist(whitelist, codeToName);

        // 2. code→node_id（落库 question_node 用）
        Map<String, Long> codeToId = graphQueryService.codeToId(courseId);

        // 3. 逐题标注
        for (AnnotateRequest.Item item : req.getItems()) {
            results.add(annotateOne(courseId, item, whitelist, aiWhitelist, codeToId));
        }
        return results;
    }

    /** 标注单题：超纲（mainPoint=null）不强标；校验未过也不落库；合法则落库。 */
    private AnnotateItemResult annotateOne(Long courseId, AnnotateRequest.Item item,
            Set<String> whitelist, List<String[]> aiWhitelist, Map<String, Long> codeToId) {
        String stem = item.getStem() == null ? null : item.getStem().trim();

        AnnotateItemResult result = new AnnotateItemResult();
        result.setStem(stem);
        result.setPersisted(false);

        // 选项整理为 AI 入参 [key, text]
        List<String[]> aiOptions = new ArrayList<>();
        if (item.getOptions() != null) {
            for (AnnotateRequest.Option o : item.getOptions()) {
                aiOptions.add(new String[]{o.getKey(), o.getText()});
            }
        }

        // 调 AI 标注
        AiAnnotation ann = aiClient.annotate(stem, aiOptions, aiWhitelist);
        if (ann == null) {
            result.setReason("AI 未返回标注结果");
            return result;
        }
        result.setSubPoints(ann.getSubPoints());
        result.setReason(ann.getReason());

        // 超纲：mainPoint=null → 不强标，带 reason 回传
        if (ann.getMainPoint() == null) {
            return result;
        }

        // 不信任 AI：mainPoint/subPoints/干扰项考点须⊆白名单，否则不落库
        if (!annotationInWhitelist(ann, whitelist)) {
            result.setReason("标注考点超出课程图谱白名单");
            return result;
        }

        // 合法 → 落库
        persistAnnotated(courseId, stem, item, ann, codeToId);
        result.setMainPoint(ann.getMainPoint());
        result.setPersisted(true);
        return result;
    }

    /** 标注考点是否全部在白名单内：mainPoint、subPoints、每个干扰项 point_node_code（非空时）。 */
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

    /** 落库已标注的存量题：question(source=1,status=PENDING) → option → question_node。 */
    private void persistAnnotated(Long courseId, String stem, AnnotateRequest.Item item,
            AiAnnotation ann, Map<String, Long> codeToId) {
        // 答案 = 第一个 correct 选项的 key（无则 null）
        String answerKey = null;
        if (item.getOptions() != null) {
            for (AnnotateRequest.Option o : item.getOptions()) {
                if (Boolean.TRUE.equals(o.getCorrect())) {
                    answerKey = o.getKey();
                    break;
                }
            }
        }

        Question question = new Question();
        question.setCourseId(courseId);
        question.setStem(stem);
        question.setType(1); // 单选
        question.setDifficulty(DEFAULT_DIFFICULTY); // 请求无难度，取默认
        question.setAnswer(answerKey);
        question.setAnalysis(null);
        question.setSource(1); // 学校题库（存量）
        question.setStatus(QuestionStatus.PENDING);
        questionMapper.insert(question);
        Long questionId = question.getId();

        // 干扰项考点映射：optionKey → point_node_code
        Map<String, String> optionPoint = new HashMap<>();
        if (ann.getDistractors() != null) {
            for (AiDistractor d : ann.getDistractors()) {
                if (d.getOptionKey() != null) {
                    optionPoint.put(d.getOptionKey(), d.getPointNodeCode());
                }
            }
        }

        // 选项落库：正确项 point_node_code 置空，干扰项取标注映射
        if (item.getOptions() != null) {
            for (AnnotateRequest.Option o : item.getOptions()) {
                QuestionOption option = new QuestionOption();
                option.setQuestionId(questionId);
                option.setOptionKey(o.getKey());
                option.setOptionText(o.getText());
                boolean correct = Boolean.TRUE.equals(o.getCorrect());
                option.setIsCorrect(correct ? 1 : 0);
                option.setPointNodeCode(correct ? null : optionPoint.get(o.getKey()));
                questionOptionMapper.insert(option);
            }
        }

        // 题-知识点：主点 weight=1，次点 weight=2
        insertQuestionNode(questionId, ann.getMainPoint(), WEIGHT_MAIN, codeToId);
        if (ann.getSubPoints() != null) {
            for (String sub : ann.getSubPoints()) {
                insertQuestionNode(questionId, sub, WEIGHT_SUB, codeToId);
            }
        }
    }

    /** 课程节点 code→name 映射（缺名时由调用方回退到 code）。 */
    private Map<String, String> codeToNameMap(Long courseId) {
        List<KgNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));
        Map<String, String> codeToName = new HashMap<>();
        for (KgNode n : nodes) {
            codeToName.put(n.getNodeCode(), n.getName());
        }
        return codeToName;
    }

    /** 把白名单 codes 拼成 AI 入参 [code, name]（缺名回退到 code）。出题/标注共用。 */
    private List<String[]> buildAiWhitelist(Set<String> codes, Map<String, String> codeToName) {
        List<String[]> aiWhitelist = new ArrayList<>();
        for (String code : codes) {
            aiWhitelist.add(new String[]{code, codeToName.getOrDefault(code, code)});
        }
        return aiWhitelist;
    }

    /**
     * 代码侧校验（不信任 AI）：
     *   (a) 恰一个 correct 选项（答案天然在选项内）；
     *   (b) mainPoint∈白名单、sub_points⊆白名单、每个干扰项 point_node_code 空或∈白名单。
     */
    private boolean isValid(AiQuestion q, Set<String> whitelist) {
        if (q == null || !StringUtils.hasText(q.getStem()) || q.getOptions() == null || q.getOptions().isEmpty()) {
            return false;
        }
        // (a) 恰一个正确项
        int correctCount = 0;
        for (AiDistractor opt : q.getOptions()) {
            if (Boolean.TRUE.equals(opt.getCorrect())) {
                correctCount++;
            }
        }
        if (correctCount != 1) {
            return false;
        }
        // (b) 主考点
        if (!whitelist.contains(q.getMainPoint())) {
            return false;
        }
        // 次考点
        if (q.getSubPoints() != null) {
            for (String sub : q.getSubPoints()) {
                if (!whitelist.contains(sub)) {
                    return false;
                }
            }
        }
        // 干扰项考点（空/null 允许，否则须在白名单内）
        for (AiDistractor opt : q.getOptions()) {
            String pc = opt.getPointNodeCode();
            if (StringUtils.hasText(pc) && !whitelist.contains(pc)) {
                return false;
            }
        }
        return true;
    }

    /** 同课程下该 trim 后的题干是否已存在。 */
    private boolean existsStem(Long courseId, String stem) {
        return !questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getCourseId, courseId)
                .eq(Question::getStem, stem)).isEmpty();
    }

    /** 落库 question→option→question_node，返回生成的 question.id。 */
    private Long persist(Long courseId, String stem, AiQuestion q, Map<String, Long> codeToId) {
        // 正确项 key（已校验恰一个）
        String answerKey = null;
        for (AiDistractor opt : q.getOptions()) {
            if (Boolean.TRUE.equals(opt.getCorrect())) {
                answerKey = opt.getOptionKey();
                break;
            }
        }

        Question question = new Question();
        question.setCourseId(courseId);
        question.setStem(stem);
        question.setType(1); // 单选
        question.setDifficulty(q.getDifficulty() == null ? DEFAULT_DIFFICULTY : q.getDifficulty());
        question.setAnswer(answerKey);
        question.setAnalysis(q.getAnalysis());
        question.setSource(2); // AI 生成
        question.setStatus(QuestionStatus.PENDING);
        questionMapper.insert(question);
        Long questionId = question.getId();

        // 选项：正确项 point_node_code 置空，干扰项保留
        for (AiDistractor opt : q.getOptions()) {
            QuestionOption option = new QuestionOption();
            option.setQuestionId(questionId);
            option.setOptionKey(opt.getOptionKey());
            option.setOptionText(opt.getText());
            boolean correct = Boolean.TRUE.equals(opt.getCorrect());
            option.setIsCorrect(correct ? 1 : 0);
            option.setPointNodeCode(correct ? null : opt.getPointNodeCode());
            questionOptionMapper.insert(option);
        }

        // 题-知识点：主点 weight=1，次点 weight=2（code→id，缺映射防御性跳过）
        insertQuestionNode(questionId, q.getMainPoint(), WEIGHT_MAIN, codeToId);
        if (q.getSubPoints() != null) {
            for (String sub : q.getSubPoints()) {
                insertQuestionNode(questionId, sub, WEIGHT_SUB, codeToId);
            }
        }
        return questionId;
    }

    private void insertQuestionNode(Long questionId, String code, int weight, Map<String, Long> codeToId) {
        Long nodeId = codeToId.get(code);
        if (nodeId == null) {
            log.warn("题-知识点关联跳过：code={} 无 node_id 映射", code);
            return;
        }
        QuestionNode qn = new QuestionNode();
        qn.setQuestionId(questionId);
        qn.setNodeId(nodeId);
        qn.setWeight(weight);
        questionNodeMapper.insert(qn);
    }
}
