package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.dto.AiAnnotation;
import com.wenjin.ai.dto.AiDistractor;
import com.wenjin.ai.dto.AiQuestion;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AnnotateRequest;
import com.wenjin.dto.CodeCaseItem;
import com.wenjin.dto.ImportBankResult;
import com.wenjin.dto.QuestionBankFile;
import com.wenjin.entity.Course;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionCodeCase;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.QuestionCodeCaseMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.support.CodeQuestionRules;
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.QuestionType;
import com.wenjin.support.StemHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题目短写事务：仅 DB 写入，不含 AI / 网络 / Excel 解析。
 * <p>由编排层（出题、标注、导入）在事务外完成 AI 后调用本服务落库。
 */
@Service
public class QuestionWriteService {

    private static final Logger log = LoggerFactory.getLogger(QuestionWriteService.class);

    private static final int DEFAULT_DIFFICULTY = 3;
    private static final int WEIGHT_MAIN = 1;
    private static final int WEIGHT_SUB = 2;

    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final CourseMapper courseMapper;
    private final GraphQueryService graphQueryService;
    private final QuestionCodeCaseMapper codeCaseMapper;

    /** 编程题用例数上限（wenjin.limits.code.max-cases；字段默认值供单测直接 new 使用）。 */
    @Value("${wenjin.limits.code.max-cases:20}")
    private int codeMaxCases = 20;

    /** 编程题起始代码长度上限（wenjin.limits.code.source-max-chars）。 */
    @Value("${wenjin.limits.code.source-max-chars:10000}")
    private int codeSourceMaxChars = 10000;

    public QuestionWriteService(QuestionMapper questionMapper,
                                QuestionOptionMapper questionOptionMapper,
                                QuestionNodeMapper questionNodeMapper,
                                CourseMapper courseMapper,
                                GraphQueryService graphQueryService,
                                QuestionCodeCaseMapper codeCaseMapper) {
        this.questionMapper = questionMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.questionNodeMapper = questionNodeMapper;
        this.courseMapper = courseMapper;
        this.graphQueryService = graphQueryService;
        this.codeCaseMapper = codeCaseMapper;
    }

    /**
     * 落库 AI 生成题：question(source=2,status=PENDING) + option + question_node。
     * 唯一键冲突 → {@link ResultCode#CONFLICT}（HTTP 409）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long persistGenerated(Long courseId, String stem, AiQuestion q, Map<String, Long> codeToId) {
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
        question.setStemHash(StemHash.sha256Hex(stem));
        question.setType(1);
        question.setDifficulty(q.getDifficulty() == null ? DEFAULT_DIFFICULTY : q.getDifficulty());
        question.setAnswer(answerKey);
        question.setAnalysis(q.getAnalysis());
        question.setSource(2);
        question.setStatus(QuestionStatus.PENDING);
        insertQuestionOrConflict(question);
        Long questionId = question.getId();

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

        insertQuestionNode(questionId, q.getMainPoint(), WEIGHT_MAIN, codeToId);
        if (q.getSubPoints() != null) {
            for (String sub : q.getSubPoints()) {
                insertQuestionNode(questionId, sub, WEIGHT_SUB, codeToId);
            }
        }
        return questionId;
    }

    /**
     * 落库已标注存量题：question(source=1,status=PENDING) + option + question_node。
     * 唯一键冲突 → {@link ResultCode#CONFLICT}。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long persistAnnotated(Long courseId, String stem, AnnotateRequest.Item item,
                                 AiAnnotation ann, Map<String, Long> codeToId) {
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
        question.setStemHash(StemHash.sha256Hex(stem));
        question.setType(1);
        question.setDifficulty(DEFAULT_DIFFICULTY);
        question.setAnswer(answerKey);
        question.setAnalysis(null);
        question.setSource(1);
        question.setStatus(QuestionStatus.PENDING);
        insertQuestionOrConflict(question);
        Long questionId = question.getId();

        Map<String, String> optionPoint = new HashMap<>();
        if (ann.getDistractors() != null) {
            for (AiDistractor d : ann.getDistractors()) {
                if (d.getOptionKey() != null) {
                    optionPoint.put(d.getOptionKey(), d.getPointNodeCode());
                }
            }
        }

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

        insertQuestionNode(questionId, ann.getMainPoint(), WEIGHT_MAIN, codeToId);
        if (ann.getSubPoints() != null) {
            for (String sub : ann.getSubPoints()) {
                insertQuestionNode(questionId, sub, WEIGHT_SUB, codeToId);
            }
        }
        return questionId;
    }

    /**
     * 题库批量落库（短事务，无 AI）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportBankResult persistBank(Long courseId, QuestionBankFile bank, boolean aiCleaned) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在: " + courseId);
        }

        Map<String, Long> codeToId = graphQueryService.codeToId(courseId);
        ImportBankResult result = new ImportBankResult();
        result.setAiCleaned(aiCleaned);

        if (bank.getQuestions() == null) {
            return result;
        }

        Set<String> validCodes = codeToId.keySet();
        Set<String> existingHashes = questionMapper.selectList(
                new LambdaQueryWrapper<Question>().eq(Question::getCourseId, courseId)
        ).stream().map(this::fingerprintOf).collect(Collectors.toSet());

        for (QuestionBankFile.BankQuestion bq : bank.getQuestions()) {
            String stem = bq.getStem() == null ? null : bq.getStem().trim();
            if (!StringUtils.hasText(stem)) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            String nodeCode = bq.getNodeCode();
            if (nodeCode == null || !validCodes.contains(nodeCode)) {
                result.setSkipped(result.getSkipped() + 1);
                result.setInvalidNodeSkipped(result.getInvalidNodeSkipped() + 1);
                log.warn("题库导入跳过：nodeCode「{}」不在课程图谱内，题干前 20 字「{}」",
                        nodeCode, stemPreview(stem));
                continue;
            }
            // 仅 type=CODE 走编程题分支；其它取值（含 AI 清洗可能带出的 type）沿用旧的单选行为
            boolean isCode = bq.getType() != null && "CODE".equalsIgnoreCase(bq.getType().trim());
            if (isCode) {
                String codeError = CodeQuestionRules.validate(bq.getStarterCode(), bq.getCases(),
                        codeMaxCases, codeSourceMaxChars);
                if (codeError != null) {
                    result.setSkipped(result.getSkipped() + 1);
                    result.setInvalidCodeSkipped(result.getInvalidCodeSkipped() + 1);
                    log.warn("题库导入跳过编程题：{}，题干前 20 字「{}」", codeError, stemPreview(stem));
                    continue;
                }
            }
            String hash = StemHash.sha256Hex(stem);
            if (existingHashes.contains(hash)) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            try {
                persistBankQuestion(courseId, stem, hash, bq, codeToId, isCode);
                existingHashes.add(hash);
                result.setImported(result.getImported() + 1);
            } catch (BusinessException ex) {
                if (ex.getCode() == ResultCode.CONFLICT.getCode()) {
                    existingHashes.add(hash);
                    result.setSkipped(result.getSkipped() + 1);
                } else {
                    throw ex;
                }
            }
        }
        log.info("题库导入完成 courseId={} -> imported={} skipped={}（其中 nodeCode 非法 {}、编程题非法 {}）aiCleaned={}",
                courseId, result.getImported(), result.getSkipped(),
                result.getInvalidNodeSkipped(), result.getInvalidCodeSkipped(), aiCleaned);
        return result;
    }

    private void persistBankQuestion(Long courseId, String stem, String stemHash,
                                     QuestionBankFile.BankQuestion bq, Map<String, Long> codeToId,
                                     boolean isCode) {
        String answerKey = null;
        if (!isCode && bq.getOptions() != null) {
            for (QuestionBankFile.BankOption o : bq.getOptions()) {
                if (Boolean.TRUE.equals(o.getCorrect())) {
                    answerKey = o.getKey();
                    break;
                }
            }
        }

        Question question = new Question();
        question.setCourseId(courseId);
        question.setStem(stem);
        question.setStemHash(stemHash);
        question.setType(isCode ? QuestionType.CODE : 1);
        question.setDifficulty(bq.getDifficulty() == null ? DEFAULT_DIFFICULTY : bq.getDifficulty());
        question.setAnswer(answerKey);
        question.setAnalysis(bq.getAnalysis());
        if (isCode) {
            question.setStarterCode(StringUtils.hasText(bq.getStarterCode()) ? bq.getStarterCode() : null);
            question.setCodeLanguage("java");
        }
        question.setSource(1);
        question.setStatus(QuestionStatus.APPROVED);
        insertQuestionOrConflict(question);
        Long questionId = question.getId();

        if (isCode) {
            replaceCodeCases(questionId, bq.getCases());
        }

        if (!isCode && bq.getOptions() != null) {
            for (QuestionBankFile.BankOption o : bq.getOptions()) {
                boolean correct = Boolean.TRUE.equals(o.getCorrect());
                String pnc = o.getPointNodeCode();
                String effectivePnc = (correct || pnc == null || !codeToId.containsKey(pnc)) ? null : pnc;

                QuestionOption option = new QuestionOption();
                option.setQuestionId(questionId);
                option.setOptionKey(o.getKey());
                option.setOptionText(o.getText());
                option.setIsCorrect(correct ? 1 : 0);
                option.setPointNodeCode(effectivePnc);
                questionOptionMapper.insert(option);
            }
        }

        insertQuestionNode(questionId, bq.getNodeCode(), WEIGHT_MAIN, codeToId);
    }

    /**
     * 整体替换某编程题的测试用例（seq 从 1 起按给定顺序编号）。调用方须已完成校验。
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceCodeCases(Long questionId, List<CodeCaseItem> cases) {
        codeCaseMapper.delete(new LambdaQueryWrapper<QuestionCodeCase>()
                .eq(QuestionCodeCase::getQuestionId, questionId));
        if (cases == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int seq = 1;
        for (CodeCaseItem c : cases) {
            QuestionCodeCase row = new QuestionCodeCase();
            row.setQuestionId(questionId);
            row.setSeq(seq++);
            row.setStdin(c.getStdin() == null ? "" : c.getStdin());
            row.setExpectedStdout(c.getExpectedStdout());
            row.setHidden(Boolean.TRUE.equals(c.getHidden()) ? 1 : 0);
            row.setCreatedAt(now);
            codeCaseMapper.insert(row);
        }
    }

    private void insertQuestionOrConflict(Question question) {
        try {
            questionMapper.insert(question);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ResultCode.CONFLICT, "题目题干已存在（指纹冲突）");
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateKey(ex)) {
                throw new BusinessException(ResultCode.CONFLICT, "题目题干已存在（指纹冲突）");
            }
            throw ex;
        }
    }

    private static boolean isDuplicateKey(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getErrorCode() == 1062) {
                return true;
            }
            t = t.getCause();
        }
        return false;
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

    private String fingerprintOf(Question q) {
        if (StringUtils.hasText(q.getStemHash())) {
            return q.getStemHash();
        }
        return StemHash.sha256Hex(q.getStem());
    }

    private String stemPreview(String stem) {
        if (stem == null) {
            return "";
        }
        return stem.length() > 20 ? stem.substring(0, 20) : stem;
    }
}
