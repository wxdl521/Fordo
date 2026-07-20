package com.wenjin.service.companion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.PracticeService;
import com.wenjin.service.ReviewService;
import com.wenjin.service.trace.AgentTraceService;
import com.wenjin.service.trace.StepRecord;
import com.wenjin.service.trace.TraceContext;
import com.wenjin.support.AgentStepStatus;
import com.wenjin.support.AgentStepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Zero-LLM companion degrade path (M9).
 *
 * <p>Triggers: LLM timeout / failure count ≥ 2 / {@code companion-llm-enabled=false}.
 * Returns a template response and optionally a practice card picked by
 * priority: due review &gt; weak mastery &gt; learning frontier.
 */
@Service
public class CompanionDegradeService {

    private static final Logger log = LoggerFactory.getLogger(CompanionDegradeService.class);

    public static final String REASON_LLM_DISABLED = "LLM_DISABLED";
    public static final String REASON_LLM_TIMEOUT = "LLM_TIMEOUT";
    public static final String REASON_LLM_FAILURES = "LLM_FAILURES";

    private static final String TEMPLATE =
            "我这边暂时无法调用大模型，先给你一条不依赖 AI 的学习建议：优先完成今日复习，"
                    + "再针对薄弱点做短练习。需要练习时直接点下方卡片，或稍后再来问我。";

    private final KgNodeMapper nodeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final MasteryDecayCalculator decayCalculator;
    private final ReviewService reviewService;
    private final PracticeService practiceService;
    private final double masteredThreshold;

    @Autowired(required = false)
    private AgentTraceService agentTraceService;

    public CompanionDegradeService(KgNodeMapper nodeMapper,
                                   StudentMasteryMapper masteryMapper,
                                   MasteryDecayCalculator decayCalculator,
                                   ReviewService reviewService,
                                   PracticeService practiceService,
                                   @Value("${wenjin.mastery.mastered-threshold:75}") double masteredThreshold) {
        this.nodeMapper = Objects.requireNonNull(nodeMapper);
        this.masteryMapper = Objects.requireNonNull(masteryMapper);
        this.decayCalculator = Objects.requireNonNull(decayCalculator);
        this.reviewService = Objects.requireNonNull(reviewService);
        this.practiceService = Objects.requireNonNull(practiceService);
        this.masteredThreshold = masteredThreshold;
    }

    public record DegradeResult(String text, PracticeStartVO practiceCard, String reason) {}

    /**
     * Build zero-LLM response + optional practice card; records DEGRADE step when trace present.
     */
    public DegradeResult degrade(Long studentId,
                                 Long courseId,
                                 String focusNodeCode,
                                 String reason,
                                 TraceContext traceCtx) {
        String r = StringUtils.hasText(reason) ? reason.trim() : REASON_LLM_FAILURES;
        recordDegradeStep(traceCtx, r);

        PracticeStartVO card = null;
        try {
            Optional<NodePick> pick = pickPracticeTarget(studentId, courseId, focusNodeCode);
            if (pick.isPresent()) {
                NodePick p = pick.get();
                card = practiceService.start(studentId, courseId, p.nodeId(), null, 2);
            }
        } catch (Exception e) {
            log.warn("degrade practice card pick failed: {}", e.getMessage());
        }

        String text = TEMPLATE;
        if (card != null && card.getNode() != null && StringUtils.hasText(card.getNode().getNodeCode())) {
            text = TEMPLATE + " 已为你准备「" + card.getNode().getName()
                    + "」（" + card.getNode().getNodeCode() + "）短练习。";
        }
        return new DegradeResult(text, card, r);
    }

    /**
     * Priority: due review &gt; weak mastery &gt; frontier (focus node if set, else first course node).
     */
    Optional<NodePick> pickPracticeTarget(Long studentId, Long courseId, String focusNodeCode) {
        // 1) due review
        try {
            ReviewTodayVO today = reviewService.getToday(studentId, courseId);
            if (today != null && today.getItems() != null && !today.getItems().isEmpty()) {
                ReviewTodayVO.ReviewItemVO first = today.getItems().get(0);
                if (first != null && StringUtils.hasText(first.getNodeCode())) {
                    KgNode n = findNode(courseId, first.getNodeCode());
                    if (n != null) {
                        return Optional.of(new NodePick(n.getId(), n.getNodeCode(), "DUE"));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("review queue unavailable: {}", e.getMessage());
        }

        // 2) weak mastery (lowest effective first)
        List<StudentMastery> masteries = masteryMapper.selectList(
                new LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .eq(StudentMastery::getCourseId, courseId));
        if (masteries != null && !masteries.isEmpty()) {
            Optional<StudentMastery> weakest = masteries.stream()
                    .filter(Objects::nonNull)
                    .filter(m -> m.getMasteryScore() != null)
                    .min(Comparator.comparingDouble(this::effective));
            if (weakest.isPresent() && effective(weakest.get()) < masteredThreshold) {
                StudentMastery m = weakest.get();
                KgNode n = nodeMapper.selectById(m.getNodeId());
                if (n != null && n.getArchivedAt() == null) {
                    return Optional.of(new NodePick(n.getId(), n.getNodeCode(), "WEAK"));
                }
            }
        }

        // 3) frontier / focus fallback
        if (StringUtils.hasText(focusNodeCode)) {
            KgNode focus = findNode(courseId, focusNodeCode.trim());
            if (focus != null) {
                return Optional.of(new NodePick(focus.getId(), focus.getNodeCode(), "FOCUS"));
            }
        }
        List<KgNode> any = nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .isNull(KgNode::getArchivedAt)
                .last("LIMIT 1"));
        if (any != null && !any.isEmpty()) {
            KgNode n = any.get(0);
            return Optional.of(new NodePick(n.getId(), n.getNodeCode(), "FRONTIER"));
        }
        return Optional.empty();
    }

    private double effective(StudentMastery m) {
        if (m == null || m.getMasteryScore() == null) {
            return 0;
        }
        try {
            return decayCalculator.effectiveScoreOf(m);
        } catch (Exception e) {
            return m.getMasteryScore().doubleValue();
        }
    }

    private KgNode findNode(Long courseId, String nodeCode) {
        return nodeMapper.selectOne(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .eq(KgNode::getNodeCode, nodeCode)
                .isNull(KgNode::getArchivedAt));
    }

    private void recordDegradeStep(TraceContext ctx, String reason) {
        if (agentTraceService == null || ctx == null || ctx.isNoop()) {
            return;
        }
        try {
            agentTraceService.step(ctx, new StepRecord(
                    AgentStepType.DEGRADE,
                    reason,
                    AgentStepStatus.OK,
                    "{}",
                    "{\"degraded\":true,\"reason\":\"" + reason + "\"}",
                    0,
                    0,
                    0,
                    reason));
        } catch (Exception e) {
            log.warn("DEGRADE step failed: {}", e.getMessage());
        }
    }

    record NodePick(Long nodeId, String nodeCode, String tier) {}
}
