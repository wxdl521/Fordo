package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CompanionToolProperties;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.dto.CompanionConversationVO;
import com.wenjin.dto.CompanionMessageVO;
import com.wenjin.dto.DiagnosticResultVO;
import com.wenjin.dto.LearningPathVO;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.entity.CompanionMessage;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.CompanionConversationMapper;
import com.wenjin.mapper.CompanionMessageMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.companion.CompanionTool;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.service.companion.ToolExecutionResult;
import com.wenjin.service.companion.ToolExecutionState;
import com.wenjin.service.companion.ToolGovernor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompanionServiceImpl implements CompanionService {

    private static final Logger log = LoggerFactory.getLogger(CompanionServiceImpl.class);
    private static final int ROLE_USER = 1;
    private static final int ROLE_AI = 2;

    private final CompanionConversationMapper conversationMapper;
    private final CompanionMessageMapper messageMapper;
    private final KgNodeMapper nodeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final CourseMapper courseMapper;
    private final PathService pathService;
    private final DiagnosticResultService diagnosticResultService;
    private final CompanionAiClient aiClient;
    private final MasteryDecayCalculator decayCalculator;
    private final CourseService courseService;

    @Autowired(required = false)
    private ToolGovernor toolGovernor;

    @Autowired(required = false)
    private List<CompanionTool> companionTools = List.of();

    @Autowired(required = false)
    private CompanionToolProperties toolProperties = disabledToolProperties();

    @Override
    @Transactional
    public Long startTurn(CompanionChatRequest req) {
        if (req.getStudentId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "studentId 不能为空");
        }
        if (req.getCourseId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseId 不能为空");
        }
        if (req.getMessage() == null || req.getMessage().trim().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息内容不能为空");
        }

        Long conversationId = req.getConversationId();
        if (conversationId == null) {
            CompanionConversation conversation = new CompanionConversation();
            conversation.setStudentId(req.getStudentId());
            conversation.setCourseId(req.getCourseId());
            conversation.setNodeCode(req.getNodeCode());
            conversation.setTitle(buildTitle(req));
            conversation.setCreatedAt(LocalDateTime.now());
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.insert(conversation);
            conversationId = conversation.getId();
        } else {
            CompanionConversation conversation = conversationMapper.selectById(conversationId);
            if (conversation == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在: " + conversationId);
            }
            if (!Objects.equals(conversation.getStudentId(), req.getStudentId())
                    || !Objects.equals(conversation.getCourseId(), req.getCourseId())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该会话");
            }
            if (req.getNodeCode() != null && !req.getNodeCode().isBlank()) {
                conversation.setNodeCode(req.getNodeCode());
            }
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }

        CompanionMessage userMessage = new CompanionMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(req.getMessage());
        userMessage.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMessage);
        return conversationId;
    }

    @Override
    public void streamReply(Long conversationId, Consumer<String> onToken) {
        streamReply(conversationId, new CompanionStreamSink() {
            @Override
            public void token(String token) {
                onToken.accept(token);
            }
        });
    }

    @Override
    public void streamReply(Long conversationId, CompanionStreamSink sink) {
        CompanionConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在: " + conversationId);
        }

        List<CompanionMessage> history = messageMapper.selectList(
                new LambdaQueryWrapper<CompanionMessage>()
                        .eq(CompanionMessage::getConversationId, conversationId)
                        .orderByAsc(CompanionMessage::getCreatedAt));
        if (history.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "会话无历史消息");
        }

        List<CompanionTool> usableTools = usableTools();
        boolean toolOrchestrationAvailable = toolOrchestrationAvailable(usableTools);
        String systemPrompt = buildSystemPromptForStudent(
                conversation.getStudentId(),
                conversation.getCourseId(),
                conversation.getNodeCode(),
                toolOrchestrationAvailable);

        if (!toolOrchestrationAvailable) {
            streamWithLegacyPath(conversationId, systemPrompt, history, sink);
            return;
        }

        streamWithTools(conversationId, conversation, systemPrompt, history, usableTools, sink);
    }

    @Override
    public List<CompanionConversationVO> listConversations(Long studentId, Long courseId) {
        List<CompanionConversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<CompanionConversation>()
                        .eq(CompanionConversation::getStudentId, studentId)
                        .eq(CompanionConversation::getCourseId, courseId)
                        .orderByDesc(CompanionConversation::getUpdatedAt));

        return conversations.stream().map(conversation -> {
            CompanionConversationVO vo = new CompanionConversationVO();
            vo.setId(conversation.getId());
            vo.setTitle(conversation.getTitle());
            vo.setNodeCode(conversation.getNodeCode());
            vo.setUpdatedAt(conversation.getUpdatedAt());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public List<CompanionMessageVO> getMessages(Long conversationId) {
        assertConversationOwner(conversationId);
        List<CompanionMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<CompanionMessage>()
                        .eq(CompanionMessage::getConversationId, conversationId)
                        .orderByAsc(CompanionMessage::getCreatedAt));

        return messages.stream().map(message -> {
            CompanionMessageVO vo = new CompanionMessageVO();
            vo.setRole(message.getRole() == ROLE_USER ? "user" : "ai");
            vo.setContent(message.getContent());
            vo.setCreatedAt(message.getCreatedAt());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long conversationId) {
        assertConversationOwner(conversationId);
        messageMapper.delete(new LambdaQueryWrapper<CompanionMessage>()
                .eq(CompanionMessage::getConversationId, conversationId));
        conversationMapper.deleteById(conversationId);
    }

    private void streamWithLegacyPath(Long conversationId,
                                      String systemPrompt,
                                      List<CompanionMessage> history,
                                      CompanionStreamSink sink) {
        List<CompanionAiClient.ChatMsg> historyMsgs = new ArrayList<>();
        for (int i = 0; i < history.size() - 1; i++) {
            CompanionMessage message = history.get(i);
            historyMsgs.add(new CompanionAiClient.ChatMsg(toRole(message), message.getContent()));
        }

        String latestUserMessage = history.get(history.size() - 1).getContent();
        StringBuilder fullReply = new StringBuilder();
        aiClient.stream(systemPrompt, historyMsgs, latestUserMessage, token -> {
            fullReply.append(token);
            sink.token(token);
        });
        saveAiMessage(conversationId, fullReply.toString());
    }

    private void streamWithTools(Long conversationId,
                                 CompanionConversation conversation,
                                 String systemPrompt,
                                 List<CompanionMessage> history,
                                 List<CompanionTool> tools,
                                 CompanionStreamSink sink) {
        List<Map<String, Object>> messages = buildMessages(systemPrompt, history);
        List<Map<String, Object>> toolSchemas = tools.stream().map(CompanionTool::schema).toList();
        Map<String, String> toolLabels = buildToolLabels(tools);
        ToolExecutionState state = new ToolExecutionState(maxToolCalls());
        ToolContext context = new ToolContext(conversation.getStudentId(), conversation.getCourseId());

        for (int round = 0; round < maxRounds(); round++) {
            CompanionAiClient.TurnOutcome outcome;
            try {
                outcome = aiClient.completeWithTools(messages, toolSchemas);
            } catch (Exception ex) {
                log.warn("Companion tool resolution failed for conversationId={}, falling back to legacy stream",
                        conversationId, ex);
                String fallbackPrompt = buildSystemPromptForStudent(
                        conversation.getStudentId(),
                        conversation.getCourseId(),
                        conversation.getNodeCode(),
                        false);
                streamWithLegacyPath(conversationId, fallbackPrompt, history, sink);
                return;
            }
            if (outcome instanceof CompanionAiClient.FinalText) {
                break;
            }
            if (!(outcome instanceof CompanionAiClient.ToolCalls toolCalls) || toolCalls.calls().isEmpty()) {
                break;
            }

            messages.add(assistantToolCallMessage(toolCalls.calls()));
            for (CompanionAiClient.ToolCall call : toolCalls.calls()) {
                String toolName = call.name() == null ? "" : call.name();
                sink.toolStart(toolName, toolLabels.getOrDefault(toolName, toolName));
                ToolExecutionResult result = toolGovernor.execute(context, call, state);
                messages.add(toolMessage(result));
                sink.toolResult(toolName, result.ok());
                if (result.practiceCard() != null) {
                    sink.practiceCard(result.practiceCard());
                }
            }
        }

        StringBuilder fullReply = new StringBuilder();
        aiClient.streamMessages(messages, token -> {
            fullReply.append(token);
            sink.token(token);
        });
        saveAiMessage(conversationId, fullReply.toString());
    }

    private List<Map<String, Object>> buildMessages(String systemPrompt, List<CompanionMessage> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (CompanionMessage message : history) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", toRole(message));
            item.put("content", message.getContent());
            messages.add(item);
        }
        return messages;
    }

    private Map<String, Object> assistantToolCallMessage(List<CompanionAiClient.ToolCall> calls) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", "");
        message.put("tool_calls", calls.stream().map(this::toolCallPayload).toList());
        return message;
    }

    private Map<String, Object> toolMessage(ToolExecutionResult result) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", result.toolCallId());
        message.put("content", result.content());
        return message;
    }

    private Map<String, Object> toolCallPayload(CompanionAiClient.ToolCall call) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.name());
        function.put("arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson());

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", call.id() == null ? "" : call.id());
        item.put("type", "function");
        item.put("function", function);
        return item;
    }

    private Map<String, String> buildToolLabels(List<CompanionTool> tools) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (tools == null) {
            return labels;
        }
        for (CompanionTool tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            labels.put(tool.name(), tool.label());
        }
        return labels;
    }

    private boolean toolOrchestrationAvailable(List<CompanionTool> tools) {
        return toolGovernor != null
                && toolProperties != null
                && toolProperties.isEnabled()
                && maxRounds() > 0
                && maxToolCalls() > 0
                && tools != null
                && !tools.isEmpty();
    }

    private List<CompanionTool> usableTools() {
        if (companionTools == null || companionTools.isEmpty()) {
            return List.of();
        }
        return companionTools.stream()
                .filter(Objects::nonNull)
                .filter(tool -> tool.schema() != null)
                .toList();
    }

    private int maxRounds() {
        return toolProperties == null ? 0 : Math.max(0, toolProperties.getMaxRounds());
    }

    private int maxToolCalls() {
        return toolProperties == null ? 0 : Math.max(0, toolProperties.getMaxToolCalls());
    }

    private void saveAiMessage(Long conversationId, String content) {
        CompanionMessage aiMessage = new CompanionMessage();
        aiMessage.setConversationId(conversationId);
        aiMessage.setRole(ROLE_AI);
        aiMessage.setContent(content);
        aiMessage.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(aiMessage);
    }

    private void assertConversationOwner(Long conversationId) {
        Long currentUserId = CurrentUser.get();
        if (currentUserId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage());
        }
        CompanionConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !currentUserId.equals(conversation.getStudentId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问他人会话");
        }
        courseService.assertAccessibleByStudent(currentUserId, conversation.getCourseId());
    }

    private String buildTitle(CompanionChatRequest req) {
        String message = req.getMessage();
        String truncated = message.length() > 20 ? message.substring(0, 20) + "..." : message;

        if (req.getNodeCode() != null) {
            KgNode node = nodeMapper.selectOne(
                    new LambdaQueryWrapper<KgNode>()
                            .eq(KgNode::getCourseId, req.getCourseId())
                            .eq(KgNode::getNodeCode, req.getNodeCode()));
            if (node != null) {
                return node.getName() + "：" + truncated;
            }
        }
        return truncated;
    }

    private String buildSystemPromptForStudent(Long studentId,
                                               Long courseId,
                                               String nodeCode,
                                               boolean useToolPrompt) {
        PromptContext context = buildPromptContext(studentId, courseId, nodeCode, !useToolPrompt);
        if (useToolPrompt) {
            return buildSystemPrompt(
                    context.courseName(),
                    context.whitelist(),
                    List.of(),
                    context.targetNode(),
                    context.currentStep(),
                    context.focusNodeCode(),
                    context.focusNodeName(),
                    context.diagnostic());
        }
        return buildLegacySystemPrompt(
                context.courseName(),
                context.whitelist(),
                context.weakPoints(),
                context.targetNode(),
                context.currentStep(),
                context.focusNodeCode(),
                context.focusNodeName(),
                context.diagnostic());
    }

    private PromptContext buildPromptContext(Long studentId,
                                             Long courseId,
                                             String nodeCode,
                                             boolean includeWeakPoints) {
        List<KgNode> allNodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId));
        List<String> whitelist = allNodes.stream().map(CompanionServiceImpl::nodePromptLabel).collect(Collectors.toList());

        LearningPathVO path = null;
        try {
            path = pathService.getCurrent(studentId, courseId);
        } catch (Exception ignored) {
        }

        DiagnosticResultVO diagnostic = null;
        try {
            diagnostic = diagnosticResultService.getResult(studentId, courseId);
        } catch (Exception ignored) {
        }

        LearningPathVO.NodeRef targetNode = path == null ? null : path.getTargetNode();
        LearningPathVO.StepVO currentStep = null;
        if (path != null && path.getSteps() != null) {
            currentStep = path.getSteps().stream()
                    .filter(step -> step.getStatus() == 0)
                    .findFirst()
                    .orElse(null);
        }

        String focusNodeCode = nodeCode;
        String focusNodeName = null;
        if (focusNodeCode != null) {
            KgNode focusNode = nodeMapper.selectOne(
                    new LambdaQueryWrapper<KgNode>()
                            .eq(KgNode::getCourseId, courseId)
                            .eq(KgNode::getNodeCode, focusNodeCode));
            if (focusNode != null) {
                focusNodeName = focusNode.getName();
            }
        }

        Course course = courseMapper.selectById(courseId);
        String courseName = course != null && course.getName() != null && !course.getName().isBlank()
                ? course.getName()
                : null;

        List<String> weakPoints = includeWeakPoints ? loadWeakPoints(studentId, courseId) : List.of();
        return new PromptContext(courseName, whitelist, weakPoints, targetNode, currentStep, focusNodeCode, focusNodeName, diagnostic);
    }

    private List<String> loadWeakPoints(Long studentId, Long courseId) {
        List<StudentMastery> masteries = masteryMapper.selectList(
                new LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .eq(StudentMastery::getCourseId, courseId));

        List<StudentMastery> weakMasteries = masteries.stream()
                .filter(mastery -> mastery.getMasteryScore() != null)
                .filter(mastery -> decayCalculator.effectiveLevelOf(decayCalculator.effectiveScoreOf(mastery)) < 2)
                .toList();

        if (weakMasteries.isEmpty()) {
            return List.of();
        }

        List<Long> nodeIds = weakMasteries.stream().map(StudentMastery::getNodeId).toList();
        Map<Long, KgNode> nodeMap = nodeMapper.selectBatchIds(nodeIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(KgNode::getId, node -> node, (left, right) -> left));

        return weakMasteries.stream()
                .map(mastery -> {
                    KgNode node = nodeMap.get(mastery.getNodeId());
                    if (node == null) {
                        return null;
                    }
                    double effective = decayCalculator.effectiveScoreOf(mastery);
                    return node.getName() + "（掌握度 " + Math.round(effective) + "）";
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static String nodePromptLabel(KgNode node) {
        if (node == null) {
            return "";
        }
        String name = node.getName() == null ? "" : node.getName();
        String nodeCode = node.getNodeCode();
        if (nodeCode == null || nodeCode.isBlank()) {
            return name;
        }
        return name + "（nodeCode: " + nodeCode + "）";
    }

    public static String buildSystemPrompt(String courseName,
                                           List<String> whitelist,
                                           List<String> weakPoints,
                                           LearningPathVO.NodeRef targetNode,
                                           LearningPathVO.StepVO currentStep,
                                           String focusNodeCode,
                                           String focusNodeName,
                                           DiagnosticResultVO diagnostic) {
        String cn = (courseName == null || courseName.isBlank()) ? "课程" : courseName;
        StringBuilder sb = new StringBuilder();

        sb.append("你是「问津」的 AI 学习伙伴，专注于帮助学生理解和掌握《")
                .append(cn)
                .append("》课程的知识点。\n\n");

        appendRootCauseSection(sb, diagnostic);

        sb.append("## 课程知识点范围\n");
        if (whitelist != null) {
            for (String nodeName : whitelist) {
                sb.append("- ").append(nodeName).append("\n");
            }
        }
        sb.append("\n");

        if (targetNode != null) {
            sb.append("## 当前学习路径\n");
            sb.append("目标节点：").append(targetNode.getName()).append("（").append(targetNode.getNodeCode()).append("）\n");
            if (currentStep != null) {
                sb.append("当前步骤：").append(currentStep.getName()).append("（").append(currentStep.getNodeCode()).append("）\n");
            }
            sb.append("\n");
        }

        if (focusNodeCode != null && focusNodeName != null) {
            sb.append("## 当前焦点节点\n");
            sb.append("学生正在学习：").append(focusNodeName).append("（").append(focusNodeCode).append("）\n\n");
        }

        sb.append("## 工具使用\n");
        sb.append("- 涉及掌握度、薄弱点、证据时间时，先通过工具查询，再回答。\n");
        sb.append("- 涉及节点详情、前置关系、后继关系时，先通过工具获取节点详情，再组织解释。\n");
        sb.append("- 需要发起练习、推荐练习或确认练习入口时，先通过工具获取练习信息。\n");
        sb.append("- 用户只说节点名称时，先从课程知识点范围匹配名称；能确定 nodeCode 就用 nodeCode，否则传精确 nodeName 让服务端解析。\n");
        sb.append("- 禁止编造掌握度；没有工具证据时，要明确说明暂未查询到。\n\n");

        sb.append("## 对话规则\n");
        sb.append("- 只回答《").append(cn).append("》课程范围内的问题；超出范围时，礼貌地把话题引回当前焦点节点或学习路径目标。\n");
        sb.append("- 采用启发式教学：不要一次性给出完整答案。先点出关键，再追问一个小问题确认理解；学生卡住时再分步提示。\n");
        sb.append("- 关注优先级：诊断根因 > 当前学习路径 > 当前焦点节点。\n");
        sb.append("- 回答控制在 3 到 6 句，口语化、具体、适合聊天场景。\n");

        return sb.toString();
    }

    private static String buildLegacySystemPrompt(String courseName,
                                                  List<String> whitelist,
                                                  List<String> weakPoints,
                                                  LearningPathVO.NodeRef targetNode,
                                                  LearningPathVO.StepVO currentStep,
                                                  String focusNodeCode,
                                                  String focusNodeName,
                                                  DiagnosticResultVO diagnostic) {
        String cn = (courseName == null || courseName.isBlank()) ? "课程" : courseName;
        StringBuilder sb = new StringBuilder();

        sb.append("你是「问津」的 AI 学习伴侣，专注于帮助学生理解和掌握《").append(cn).append("》课程的知识点。\n\n");

        appendRootCauseSection(sb, diagnostic);

        sb.append("## 课程知识点范围\n");
        if (whitelist != null) {
            for (String nodeName : whitelist) {
                sb.append("- ").append(nodeName).append("\n");
            }
        }
        sb.append("\n");

        if (weakPoints != null && !weakPoints.isEmpty()) {
            sb.append("## 学生薄弱点\n");
            for (String weakPoint : weakPoints) {
                sb.append("- ").append(weakPoint).append("\n");
            }
            sb.append("\n");
        }

        if (targetNode != null) {
            sb.append("## 当前学习路径\n");
            sb.append("目标节点：").append(targetNode.getName()).append("（").append(targetNode.getNodeCode()).append("）\n");
            if (currentStep != null) {
                sb.append("当前步骤：").append(currentStep.getName()).append("（").append(currentStep.getNodeCode()).append("）\n");
            }
            sb.append("\n");
        }

        if (focusNodeCode != null && focusNodeName != null) {
            sb.append("## 当前焦点节点\n");
            sb.append("学生正在学习：").append(focusNodeName).append("（").append(focusNodeCode).append("）\n\n");
        }

        sb.append("## 对话规则\n");
        sb.append("- 只回答《").append(cn).append("》课程范围内的问题；超出范围时礼貌地把话题引导回当前焦点节点或学习路径目标。\n");
        sb.append("- 采用启发式教学：不要一次性给出完整答案。先一句话点出关键，再反问一个小问题确认理解；学生卡住时再分步提示，逐步逼近答案。\n");
        sb.append("- 关注优先级：诊断根因 > 学生薄弱点 > 当前学习路径。\n");
        sb.append("- 回答控制在 3–5 句、口语化，适合聊天气泡；多用具体例子，少长篇理论。\n");
        sb.append("- 不要编造学生未学内容的掌握情况，只依据上文提供的诊断/掌握度信息。\n");

        return sb.toString();
    }

    private static void appendRootCauseSection(StringBuilder sb, DiagnosticResultVO diagnostic) {
        if (diagnostic == null || !diagnostic.isHasWeakness()) {
            return;
        }
        DiagnosticResultVO.RootCause rootCause = diagnostic.getRootCause();
        DiagnosticResultVO.NodeRef stuckNode = diagnostic.getStuckNode();
        if (rootCause == null || stuckNode == null || rootCause.isSelf()) {
            return;
        }

        sb.append("## 诊断根因\n");
        sb.append("该学生最近一次诊断显示：当前卡点是「")
                .append(stuckNode.getName())
                .append("」，但更可能的前置根因是「")
                .append(rootCause.getName())
                .append("」掌握薄弱（掌握度 ")
                .append(fmtScore(rootCause.getMasteryScore()))
                .append("）。\n");
        if (diagnostic.getChain() != null && !diagnostic.getChain().isEmpty()) {
            String chain = diagnostic.getChain().stream()
                    .map(DiagnosticResultVO.ChainNode::getName)
                    .collect(Collectors.joining(" -> "));
            sb.append("回溯链：").append(chain).append("。\n");
        }
        sb.append("当学生问到卡点或其下游内容时，优先提醒问题可能出在更前面的「")
                .append(rootCause.getName())
                .append("」，先引导补前置，再回到当前卡点。\n\n");
    }

    private static String fmtScore(Double score) {
        return score == null ? "暂无" : String.valueOf(Math.round(score));
    }

    private static String toRole(CompanionMessage message) {
        return message.getRole() == ROLE_USER ? "user" : "assistant";
    }

    private static CompanionToolProperties disabledToolProperties() {
        CompanionToolProperties properties = new CompanionToolProperties();
        properties.setEnabled(false);
        return properties;
    }

    private record PromptContext(String courseName,
                                 List<String> whitelist,
                                 List<String> weakPoints,
                                 LearningPathVO.NodeRef targetNode,
                                 LearningPathVO.StepVO currentStep,
                                 String focusNodeCode,
                                 String focusNodeName,
                                 DiagnosticResultVO diagnostic) {
    }
}
