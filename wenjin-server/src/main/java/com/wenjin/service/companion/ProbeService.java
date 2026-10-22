package com.wenjin.service.companion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.ProbeStartVO;
import com.wenjin.dto.ProbeSubmitRequest;
import com.wenjin.dto.ProbeSubmitVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.support.AnswerGrader;
import com.wenjin.support.QuestionPicker;
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.QuestionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Probe understanding service (M9): create one-shot check questions and grade them
 * <b>without</b> writing mastery / EWMA evidence.
 */
@Service
public class ProbeService {

    private final ProbeSessionStore store;
    private final KgNodeMapper nodeMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper optionMapper;
    private final QuestionPicker questionPicker;
    /** 探针结果记录（换策略链路）；可空：未装配时只判分。 */
    private ProbeOutcomeStore outcomeStore;

    public ProbeService(ProbeSessionStore store,
                        KgNodeMapper nodeMapper,
                        QuestionMapper questionMapper,
                        QuestionOptionMapper optionMapper,
                        QuestionNodeMapper questionNodeMapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
        this.questionMapper = Objects.requireNonNull(questionMapper, "questionMapper");
        this.optionMapper = Objects.requireNonNull(optionMapper, "optionMapper");
        this.questionPicker = new QuestionPicker(questionNodeMapper, questionMapper);
    }

    @Autowired(required = false)
    public void setOutcomeStore(ProbeOutcomeStore outcomeStore) {
        this.outcomeStore = outcomeStore;
    }

    /**
     * Prefer unused bank objective question; else create EPHEMERAL template probe (not in bank).
     */
    public ProbeStartVO startProbe(Long studentId, Long courseId, String nodeCode) {
        if (studentId == null || courseId == null || !StringUtils.hasText(nodeCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "studentId/courseId/nodeCode required");
        }
        KgNode node = nodeMapper.selectOne(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .eq(KgNode::getNodeCode, nodeCode.trim())
                .isNull(KgNode::getArchivedAt));
        if (node == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "节点不存在: " + nodeCode.trim());
        }

        Set<Long> used = store.usedBankQuestionIds(studentId, courseId, node.getNodeCode());
        List<Question> picked = questionPicker.pick(
                node.getId(),
                1,
                used,
                q -> q != null
                        && q.getType() != null
                        && q.getType() != QuestionType.SHORT_ANSWER
                        && q.getType() != QuestionType.CODE
                        && (q.getStatus() == null || q.getStatus() == QuestionStatus.APPROVED));

        ProbeSessionStore.ProbeSession session;
        if (picked != null && !picked.isEmpty()) {
            session = createFromBank(studentId, courseId, node, picked.get(0));
        } else {
            session = createEphemeral(studentId, courseId, node);
        }
        return toStartVO(session, node);
    }

    /**
     * Grade probe answer. Never calls MasteryService / never writes answer_record.
     */
    public ProbeSubmitVO submit(String sessionId, ProbeSubmitRequest req) {
        if (req == null || req.getStudentId() == null || req.getCourseId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "studentId/courseId required");
        }
        ProbeSessionStore.ProbeSession session = store.find(sessionId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "探针会话不存在"));

        if (!Objects.equals(session.studentId(), req.getStudentId())
                || !Objects.equals(session.courseId(), req.getCourseId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权提交该探针会话");
        }

        if (session.submitted()) {
            return toSubmitVO(session);
        }

        String answer = req.getStudentAnswer() == null ? "" : req.getStudentAnswer().trim();
        boolean correct = grade(session, answer);
        ProbeSessionStore.ProbeSession updated = store.submit(
                        sessionId, req.getStudentId(), req.getCourseId(), correct)
                .orElseThrow(() -> new BusinessException(ResultCode.FORBIDDEN, "提交失败"));
        if (outcomeStore != null) {
            // 只在首次判分时记录（重复提交走上方幂等分支）；供下一 turn 决定是否换讲法
            outcomeStore.recordProbeResult(updated.studentId(), updated.courseId(),
                    updated.nodeCode(), Boolean.TRUE.equals(updated.correct()));
        }
        return toSubmitVO(updated);
    }

    private boolean grade(ProbeSessionStore.ProbeSession session, String studentAnswer) {
        if (session.isEphemeral()) {
            // Template ephemeral: accept any non-blank reflection, or exact key match when options present
            if (session.options() != null && !session.options().isEmpty()
                    && StringUtils.hasText(session.correctKey())) {
                return session.correctKey().trim().equalsIgnoreCase(studentAnswer.trim());
            }
            // Short free-text: non-blank counts as attempted-correct for soft probe
            return StringUtils.hasText(studentAnswer);
        }
        // Bank objective: reuse AnswerGrader with correct key set (same matching as practice)
        Set<String> keys = new HashSet<>();
        if (StringUtils.hasText(session.correctKey())) {
            for (String part : session.correctKey().split("[,，\\s]+")) {
                if (StringUtils.hasText(part)) {
                    keys.add(part.trim());
                }
            }
        }
        AnswerGrader grader = new AnswerGrader();
        int type = session.type() == null ? QuestionType.SINGLE : session.type();
        AnswerGrader.GradeResult result = grader.grade(type, studentAnswer, keys);
        return Boolean.TRUE.equals(result.correct());
    }

    private ProbeSessionStore.ProbeSession createFromBank(Long studentId,
                                                          Long courseId,
                                                          KgNode node,
                                                          Question q) {
        List<QuestionOption> options = optionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>().eq(QuestionOption::getQuestionId, q.getId()));
        List<ProbeSessionStore.ProbeOption> opts = new ArrayList<>();
        List<String> correctKeys = new ArrayList<>();
        if (options != null) {
            for (QuestionOption o : options) {
                if (o == null) {
                    continue;
                }
                opts.add(new ProbeSessionStore.ProbeOption(o.getOptionKey(), o.getOptionText()));
                if (o.getIsCorrect() != null && o.getIsCorrect() == 1) {
                    correctKeys.add(o.getOptionKey());
                }
            }
        }
        String correctKey = correctKeys.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.joining(","));
        return store.create(
                studentId,
                courseId,
                node.getNodeCode(),
                node.getId(),
                q.getStem(),
                q.getType(),
                opts,
                correctKey,
                ProbeSessionStore.Source.BANK,
                q.getId());
    }

    /**
     * EPHEMERAL template — never inserted into question bank.
     */
    private ProbeSessionStore.ProbeSession createEphemeral(Long studentId,
                                                           Long courseId,
                                                           KgNode node) {
        String name = node.getName() == null ? node.getNodeCode() : node.getName();
        String stem = "（探针·临时）请用一句话说明「" + name + "」的核心要点是什么？";
        return store.create(
                studentId,
                courseId,
                node.getNodeCode(),
                node.getId(),
                stem,
                QuestionType.SHORT_ANSWER,
                List.of(),
                null,
                ProbeSessionStore.Source.EPHEMERAL,
                null);
    }

    private static ProbeStartVO toStartVO(ProbeSessionStore.ProbeSession session, KgNode node) {
        ProbeStartVO vo = new ProbeStartVO();
        vo.setSessionId(session.sessionId());
        vo.setNodeCode(session.nodeCode());
        vo.setNodeName(node == null ? null : node.getName());
        vo.setEphemeral(session.isEphemeral());
        vo.setSource(session.source().name());
        PaperQuestionVO q = new PaperQuestionVO();
        q.setQuestionId(session.bankQuestionId()); // null for EPHEMERAL
        q.setStem(session.stem());
        q.setType(session.type());
        q.setChapter(node == null ? null : node.getChapter());
        List<PaperQuestionVO.OptionVO> opts = new ArrayList<>();
        for (ProbeSessionStore.ProbeOption o : session.options()) {
            PaperQuestionVO.OptionVO ov = new PaperQuestionVO.OptionVO();
            ov.setKey(o.key());
            ov.setText(o.text());
            opts.add(ov);
        }
        q.setOptions(opts);
        vo.setQuestion(q);
        return vo;
    }

    private static ProbeSubmitVO toSubmitVO(ProbeSessionStore.ProbeSession session) {
        ProbeSubmitVO vo = new ProbeSubmitVO();
        vo.setSessionId(session.sessionId());
        vo.setCorrect(Boolean.TRUE.equals(session.correct()));
        // Incorrect → re-explain available (ReflectionCoordinator may switch strategy)
        vo.setReexplainAvailable(!Boolean.TRUE.equals(session.correct()));
        vo.setNodeCode(session.nodeCode());
        vo.setEphemeral(session.isEphemeral());
        return vo;
    }
}
