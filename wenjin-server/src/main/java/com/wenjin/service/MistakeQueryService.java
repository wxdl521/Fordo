package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.MisconceptionSummary;
import com.wenjin.dto.MistakeReport;
import com.wenjin.dto.MistakeVO;
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
import com.wenjin.support.QuestionType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 近期错题查询（错因辅导）：只读，学生身份只来自参数（由工具上下文 / 认证主体提供）。
 *
 * <p>取 {@code is_correct=0} 的客观题（单选/多选/判断）作答，按 {@code answered_at desc}；
 * 所选<b>错误</b>选项的 {@code question_option.point_node_code} 即误区节点（拼接配方同
 * {@code DiagnosticResultServiceImpl.buildBases}）。同一题多次错只展示最近一次，但误区命中按次数累计。
 */
@Service
public class MistakeQueryService {

    public static final int STEM_MAX_CHARS = 80;
    public static final int OPTION_TEXT_MAX_CHARS = 60;
    public static final int MAX_LIMIT = 20;
    public static final int TOP_MISCONCEPTIONS = 3;
    /** 单次最多扫描的最近错题作答行（命中 V21 idx_student_course_time；有 nodeCode 时先在 SQL 里按节点预筛）。 */
    static final int SCAN_CAP = 200;

    static final int SCENE_DIAGNOSTIC = 1;
    static final int SCENE_PRACTICE = 2;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AnswerRecordMapper answerRecordMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper optionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final KgNodeMapper nodeMapper;
    private final PracticeSessionMapper practiceSessionMapper;

    public MistakeQueryService(AnswerRecordMapper answerRecordMapper,
                               QuestionMapper questionMapper,
                               QuestionOptionMapper optionMapper,
                               QuestionNodeMapper questionNodeMapper,
                               KgNodeMapper nodeMapper,
                               PracticeSessionMapper practiceSessionMapper) {
        this.answerRecordMapper = Objects.requireNonNull(answerRecordMapper, "answerRecordMapper");
        this.questionMapper = Objects.requireNonNull(questionMapper, "questionMapper");
        this.optionMapper = Objects.requireNonNull(optionMapper, "optionMapper");
        this.questionNodeMapper = Objects.requireNonNull(questionNodeMapper, "questionNodeMapper");
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
        this.practiceSessionMapper = Objects.requireNonNull(practiceSessionMapper, "practiceSessionMapper");
    }

    /** 近期错题（最新在前）。 */
    public List<MistakeVO> recentMistakes(Long studentId, Long courseId, String nodeCode,
                                          Long practiceSessionId, int limit) {
        return report(studentId, courseId, nodeCode, practiceSessionId, limit).mistakes();
    }

    /**
     * 近期错题 + 误区节点 top 汇总。
     *
     * @param nodeCode          可空；保留主节点或误区节点等于它的错题
     * @param practiceSessionId 可空；仅保留 {@code scene=2 AND session_id=?}
     * @param limit             错题条数，夹到 [1, {@value #MAX_LIMIT}]
     */
    public MistakeReport report(Long studentId, Long courseId, String nodeCode,
                                Long practiceSessionId, int limit) {
        if (studentId == null || courseId == null) {
            return MistakeReport.empty();
        }
        int lim = Math.max(1, Math.min(MAX_LIMIT, limit));
        String nodeFilter = StringUtils.hasText(nodeCode) ? nodeCode.trim() : null;

        List<AnswerRecord> records = loadWrongRecords(studentId, courseId, practiceSessionId, nodeFilter);
        if (records.isEmpty()) {
            return MistakeReport.empty();
        }
        Set<Long> questionIds = new LinkedHashSet<>();
        records.forEach(r -> questionIds.add(r.getQuestionId()));

        Map<Long, Question> questions = new HashMap<>();
        for (Question q : nullSafe(questionMapper.selectBatchIds(questionIds))) {
            if (q != null && isObjective(q.getType()) && Objects.equals(q.getCourseId(), courseId)) {
                questions.put(q.getId(), q);
            }
        }
        if (questions.isEmpty()) {
            return MistakeReport.empty();
        }

        Map<Long, Map<String, QuestionOption>> optionsByQuestion = loadOptions(questions.keySet());
        Map<Long, Long> primaryNodeIdByQuestion = loadPrimaryNodeIds(questions.keySet());
        Map<Long, KgNode> primaryNodes = loadActiveNodesById(courseId, primaryNodeIdByQuestion.values());
        Map<String, KgNode> pointNodes = loadActiveNodesByCode(courseId, pointNodeCodes(optionsByQuestion));

        List<MistakeVO> matched = new ArrayList<>();
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (AnswerRecord record : records) {
            Question q = questions.get(record.getQuestionId());
            if (q == null) {
                continue;
            }
            MistakeVO vo = toVO(record, q,
                    optionsByQuestion.getOrDefault(q.getId(), Map.of()),
                    primaryNodes.get(primaryNodeIdByQuestion.get(q.getId())),
                    pointNodes);
            if (nodeFilter != null && !touchesNode(vo, nodeFilter)) {
                continue;
            }
            vo.misconceptionNodes().forEach(n -> hits.merge(n.nodeCode(), 1, Integer::sum));
            matched.add(vo);
        }

        List<MistakeVO> mistakes = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (MistakeVO vo : matched) {
            if (mistakes.size() >= lim) {
                break;
            }
            if (seen.add(vo.questionId())) {
                mistakes.add(vo);
            }
        }
        return new MistakeReport(mistakes, topMisconceptions(hits, pointNodes));
    }

    /**
     * 复盘会话归属校验：会话须存在且属于该学生与课程，否则 NOT_FOUND（不泄露他人会话是否存在）。
     */
    public void assertPracticeSessionOwned(Long studentId, Long courseId, Long practiceSessionId) {
        PracticeSession session = practiceSessionId == null ? null : practiceSessionMapper.selectById(practiceSessionId);
        if (session == null
                || !Objects.equals(session.getStudentId(), studentId)
                || !Objects.equals(session.getCourseId(), courseId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "练习会话不存在: " + practiceSessionId);
        }
    }

    // ── loading ──

    /** nodeCode 过滤的 SQL 预筛：主节点为该节点的题 ∪ 有干扰项指向该节点的题（参数绑定，非拼接）。 */
    private static final String NODE_PREFILTER_SQL =
            "(question_id IN (SELECT qn.question_id FROM question_node qn JOIN kg_node kn ON kn.id = qn.node_id"
                    + " WHERE qn.weight = 1 AND kn.course_id = {0} AND kn.node_code = {1})"
                    + " OR question_id IN (SELECT qo.question_id FROM question_option qo"
                    + " WHERE qo.point_node_code = {1}))";

    private List<AnswerRecord> loadWrongRecords(Long studentId, Long courseId, Long practiceSessionId,
                                                String nodeFilter) {
        LambdaQueryWrapper<AnswerRecord> wrapper = new LambdaQueryWrapper<AnswerRecord>()
                .eq(AnswerRecord::getStudentId, studentId)
                .eq(AnswerRecord::getCourseId, courseId)
                .eq(AnswerRecord::getIsCorrect, 0);
        if (nodeFilter != null) {
            // 下推节点过滤：SCAN_CAP 只作用于可能涉及该节点的错题，不会被其他节点的新错题挤掉；
            // 精确判定（所选干扰项是否真指向该节点）仍在内存里做
            wrapper.apply(NODE_PREFILTER_SQL, courseId, nodeFilter);
        }
        if (practiceSessionId != null) {
            wrapper.eq(AnswerRecord::getScene, SCENE_PRACTICE)
                    .eq(AnswerRecord::getSessionId, practiceSessionId);
        }
        wrapper.orderByDesc(AnswerRecord::getAnsweredAt)
                .orderByDesc(AnswerRecord::getId)
                .last("LIMIT " + SCAN_CAP);
        List<AnswerRecord> rows = new ArrayList<>();
        // 防御式复核：SQL 条件之外再按同一语义过滤一次（mapper 返回顺序即时间倒序）
        for (AnswerRecord r : nullSafe(answerRecordMapper.selectList(wrapper))) {
            if (r == null || r.getQuestionId() == null
                    || r.getIsCorrect() == null || r.getIsCorrect() != 0
                    || !Objects.equals(r.getStudentId(), studentId)
                    || !Objects.equals(r.getCourseId(), courseId)) {
                continue;
            }
            if (practiceSessionId != null
                    && (!Objects.equals(r.getScene(), SCENE_PRACTICE)
                    || !Objects.equals(r.getSessionId(), practiceSessionId))) {
                continue;
            }
            rows.add(r);
        }
        return rows;
    }

    private Map<Long, Map<String, QuestionOption>> loadOptions(Set<Long> questionIds) {
        Map<Long, Map<String, QuestionOption>> byQuestion = new HashMap<>();
        List<QuestionOption> options = optionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>().in(QuestionOption::getQuestionId, questionIds));
        for (QuestionOption o : nullSafe(options)) {
            if (o == null || !questionIds.contains(o.getQuestionId()) || !StringUtils.hasText(o.getOptionKey())) {
                continue;
            }
            byQuestion.computeIfAbsent(o.getQuestionId(), k -> new LinkedHashMap<>())
                    .put(normalizeKey(o.getOptionKey()), o);
        }
        return byQuestion;
    }

    private Map<Long, Long> loadPrimaryNodeIds(Set<Long> questionIds) {
        List<QuestionNode> rows = questionNodeMapper.selectList(new LambdaQueryWrapper<QuestionNode>()
                .in(QuestionNode::getQuestionId, questionIds)
                .eq(QuestionNode::getWeight, 1)
                .orderByAsc(QuestionNode::getId));
        Map<Long, Long> primary = new HashMap<>();
        for (QuestionNode qn : nullSafe(rows)) {
            if (qn != null && qn.getNodeId() != null && questionIds.contains(qn.getQuestionId())
                    && Objects.equals(qn.getWeight(), 1)) {
                primary.putIfAbsent(qn.getQuestionId(), qn.getNodeId());
            }
        }
        return primary;
    }

    private Map<Long, KgNode> loadActiveNodesById(Long courseId, java.util.Collection<Long> ids) {
        Set<Long> distinct = new LinkedHashSet<>(ids);
        distinct.remove(null);
        Map<Long, KgNode> byId = new HashMap<>();
        if (distinct.isEmpty()) {
            return byId;
        }
        for (KgNode n : nullSafe(nodeMapper.selectBatchIds(distinct))) {
            if (isActiveInCourse(n, courseId)) {
                byId.put(n.getId(), n);
            }
        }
        return byId;
    }

    private Map<String, KgNode> loadActiveNodesByCode(Long courseId, Set<String> codes) {
        Map<String, KgNode> byCode = new HashMap<>();
        if (codes.isEmpty()) {
            return byCode;
        }
        List<KgNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .in(KgNode::getNodeCode, codes)
                .isNull(KgNode::getArchivedAt));
        for (KgNode n : nullSafe(nodes)) {
            if (isActiveInCourse(n, courseId) && codes.contains(n.getNodeCode())) {
                byCode.put(n.getNodeCode(), n);
            }
        }
        return byCode;
    }

    private static Set<String> pointNodeCodes(Map<Long, Map<String, QuestionOption>> optionsByQuestion) {
        Set<String> codes = new LinkedHashSet<>();
        optionsByQuestion.values().forEach(m -> m.values().forEach(o -> {
            if (StringUtils.hasText(o.getPointNodeCode())) {
                codes.add(o.getPointNodeCode().trim());
            }
        }));
        return codes;
    }

    // ── mapping ──

    private static MistakeVO toVO(AnswerRecord record,
                                  Question q,
                                  Map<String, QuestionOption> options,
                                  KgNode primary,
                                  Map<String, KgNode> pointNodes) {
        List<String> chosenKeys = parseKeys(record.getStudentAnswer());
        List<MistakeVO.OptionRef> chosen = chosenKeys.stream()
                .map(k -> new MistakeVO.OptionRef(k, optionText(options.get(k))))
                .toList();

        List<String> correctKeys = options.values().stream()
                .filter(o -> o.getIsCorrect() != null && o.getIsCorrect() == 1)
                .map(o -> normalizeKey(o.getOptionKey()))
                .sorted()
                .toList();
        if (correctKeys.isEmpty()) {
            correctKeys = parseKeys(q.getAnswer()).stream().sorted().toList();
        }
        List<MistakeVO.OptionRef> correct = correctKeys.stream()
                .map(k -> new MistakeVO.OptionRef(k, optionText(options.get(k))))
                .toList();

        Set<String> correctSet = new HashSet<>(correctKeys);
        Map<String, MistakeVO.NodeRef> misconceptions = new LinkedHashMap<>();
        for (String key : chosenKeys) {
            if (correctSet.contains(key)) {
                continue;
            }
            QuestionOption option = options.get(key);
            if (option == null || !StringUtils.hasText(option.getPointNodeCode())) {
                continue;
            }
            KgNode node = pointNodes.get(option.getPointNodeCode().trim());
            if (node != null) {
                misconceptions.putIfAbsent(node.getNodeCode(), new MistakeVO.NodeRef(node.getNodeCode(), node.getName()));
            }
        }

        boolean practice = Objects.equals(record.getScene(), SCENE_PRACTICE);
        return new MistakeVO(
                q.getId(),
                truncate(q.getStem()),
                q.getType(),
                chosen,
                correct,
                List.copyOf(misconceptions.values()),
                primary == null ? null : new MistakeVO.NodeRef(primary.getNodeCode(), primary.getName()),
                record.getAnsweredAt() == null ? null : TIME_FORMAT.format(record.getAnsweredAt()),
                practice ? "PRACTICE" : "DIAGNOSTIC",
                practice ? record.getSessionId() : null);
    }

    private static List<MisconceptionSummary> topMisconceptions(Map<String, Integer> hits,
                                                                Map<String, KgNode> pointNodes) {
        // 稳定排序：次数降序；同次数保持首次出现（更近的错题）在前
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(hits.entrySet());
        entries.sort(Comparator.comparing(Map.Entry<String, Integer>::getValue).reversed());
        List<MisconceptionSummary> top = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entries) {
            if (top.size() >= TOP_MISCONCEPTIONS) {
                break;
            }
            KgNode node = pointNodes.get(e.getKey());
            top.add(new MisconceptionSummary(e.getKey(), node == null ? null : node.getName(), e.getValue()));
        }
        return top;
    }

    private static boolean touchesNode(MistakeVO vo, String nodeCode) {
        if (vo.primaryNode() != null && nodeCode.equals(vo.primaryNode().nodeCode())) {
            return true;
        }
        return vo.misconceptionNodes().stream().anyMatch(n -> nodeCode.equals(n.nodeCode()));
    }

    static List<String> parseKeys(String answer) {
        if (!StringUtils.hasText(answer)) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String part : answer.split("[,，、\\s]+")) {
            if (StringUtils.hasText(part)) {
                keys.add(normalizeKey(part));
            }
        }
        return List.copyOf(keys);
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
    }

    private static String optionText(QuestionOption option) {
        return option == null ? null : truncate(option.getOptionText(), OPTION_TEXT_MAX_CHARS);
    }

    private static String truncate(String stem) {
        return truncate(stem, STEM_MAX_CHARS);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String s = text.strip();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static boolean isObjective(Integer type) {
        return type != null && (type == QuestionType.SINGLE || type == QuestionType.MULTI
                || type == QuestionType.TRUE_FALSE);
    }

    private static boolean isActiveInCourse(KgNode n, Long courseId) {
        return n != null && n.getArchivedAt() == null && Objects.equals(n.getCourseId(), courseId)
                && StringUtils.hasText(n.getNodeCode());
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
