package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.dto.CompanionConversationVO;
import com.wenjin.dto.CompanionMessageVO;
import com.wenjin.dto.DiagnosticResultVO;
import com.wenjin.dto.LearningPathVO;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.entity.CompanionMessage;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.CompanionConversationMapper;
import com.wenjin.mapper.CompanionMessageMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * AI 学习伴侣服务实现。
 */
@Service
@RequiredArgsConstructor
public class CompanionServiceImpl implements CompanionService {

    private static final int ROLE_USER = 1;
    private static final int ROLE_AI = 2;

    private final CompanionConversationMapper conversationMapper;
    private final CompanionMessageMapper messageMapper;
    private final KgNodeMapper nodeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final PathService pathService;
    private final DiagnosticResultService diagnosticResultService;
    private final CompanionAiClient aiClient;

    @Override
    @Transactional
    public Long startTurn(CompanionChatRequest req) {
        // 输入验证
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

        // 1. 创建或复用会话
        if (conversationId == null) {
            // 首次对话：创建新会话
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
            // 后续对话：更新会话时间
            CompanionConversation conversation = conversationMapper.selectById(conversationId);
            if (conversation == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在: " + conversationId);
            }
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }

        // 2. 保存用户消息
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
        // 1. 加载会话上下文
        CompanionConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在: " + conversationId);
        }

        // 2. 加载历史消息
        List<CompanionMessage> history = messageMapper.selectList(
            new LambdaQueryWrapper<CompanionMessage>()
                .eq(CompanionMessage::getConversationId, conversationId)
                .orderByAsc(CompanionMessage::getCreatedAt)
        );

        if (history.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "会话无历史消息");
        }

        // 3. 构建系统提示
        String systemPrompt = buildSystemPromptForStudent(
            conversation.getStudentId(),
            conversation.getCourseId(),
            conversation.getNodeCode()
        );

        // 4. 转换历史消息格式（排除最后一条用户消息，因为会作为 userMessage 参数）
        List<CompanionAiClient.ChatMsg> historyMsgs = new ArrayList<>();
        for (int i = 0; i < history.size() - 1; i++) {
            CompanionMessage msg = history.get(i);
            String role = msg.getRole() == ROLE_USER ? "user" : "assistant";
            historyMsgs.add(new CompanionAiClient.ChatMsg(role, msg.getContent()));
        }

        // 5. 获取当前用户消息
        String userMessage = history.get(history.size() - 1).getContent();

        // 6. 流式调用 AI，累积回复
        StringBuilder fullReply = new StringBuilder();
        aiClient.stream(systemPrompt, historyMsgs, userMessage, token -> {
            fullReply.append(token);
            onToken.accept(token);
        });

        // 7. 保存 AI 回复（单独事务）
        saveAiMessage(conversationId, fullReply.toString());
    }

    @Transactional
    private void saveAiMessage(Long conversationId, String content) {
        CompanionMessage aiMessage = new CompanionMessage();
        aiMessage.setConversationId(conversationId);
        aiMessage.setRole(ROLE_AI);
        aiMessage.setContent(content);
        aiMessage.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(aiMessage);
    }

    @Override
    public List<CompanionConversationVO> listConversations(Long studentId, Long courseId) {
        List<CompanionConversation> conversations = conversationMapper.selectList(
            new LambdaQueryWrapper<CompanionConversation>()
                .eq(CompanionConversation::getStudentId, studentId)
                .eq(CompanionConversation::getCourseId, courseId)
                .orderByDesc(CompanionConversation::getUpdatedAt)
        );

        return conversations.stream().map(conv -> {
            CompanionConversationVO vo = new CompanionConversationVO();
            vo.setId(conv.getId());
            vo.setTitle(conv.getTitle());
            vo.setNodeCode(conv.getNodeCode());
            vo.setUpdatedAt(conv.getUpdatedAt());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public List<CompanionMessageVO> getMessages(Long conversationId) {
        List<CompanionMessage> messages = messageMapper.selectList(
            new LambdaQueryWrapper<CompanionMessage>()
                .eq(CompanionMessage::getConversationId, conversationId)
                .orderByAsc(CompanionMessage::getCreatedAt)
        );

        return messages.stream().map(msg -> {
            CompanionMessageVO vo = new CompanionMessageVO();
            vo.setRole(msg.getRole() == ROLE_USER ? "user" : "ai");
            vo.setContent(msg.getContent());
            vo.setCreatedAt(msg.getCreatedAt());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long conversationId) {
        // 先删消息，再删会话
        messageMapper.delete(
            new LambdaQueryWrapper<CompanionMessage>()
                .eq(CompanionMessage::getConversationId, conversationId));
        conversationMapper.deleteById(conversationId);
    }

    /**
     * 构建会话标题：从节点发起则前缀节点名，否则直接截断用户问题。
     */
    private String buildTitle(CompanionChatRequest req) {
        String message = req.getMessage();
        String truncated = message.length() > 20 ? message.substring(0, 20) + "..." : message;

        if (req.getNodeCode() != null) {
            KgNode node = nodeMapper.selectOne(
                new LambdaQueryWrapper<KgNode>()
                    .eq(KgNode::getCourseId, req.getCourseId())
                    .eq(KgNode::getNodeCode, req.getNodeCode())
            );
            if (node != null) {
                return node.getName() + "：" + truncated;
            }
        }

        return truncated;
    }

    /**
     * 为学生构建系统提示（加载白名单、薄弱点、路径、焦点节点）。
     */
    private String buildSystemPromptForStudent(Long studentId, Long courseId, String nodeCode) {
        // 1. 白名单：课程下所有节点名
        List<KgNode> allNodes = nodeMapper.selectList(
            new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
        );
        List<String> whitelist = allNodes.stream()
            .map(KgNode::getName)
            .collect(Collectors.toList());

        // 2. 薄弱点：掌握等级 < 2 的节点（批量加载避免 N+1）
        List<StudentMastery> weakMasteries = masteryMapper.selectList(
            new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, studentId)
                .eq(StudentMastery::getCourseId, courseId)
                .lt(StudentMastery::getMasteryLevel, 2)
        );

        // 批量加载节点
        List<Long> nodeIds = weakMasteries.stream()
            .map(StudentMastery::getNodeId)
            .collect(Collectors.toList());
        Map<Long, KgNode> nodeMap = new HashMap<>();
        if (!nodeIds.isEmpty()) {
            List<KgNode> nodes = nodeMapper.selectBatchIds(nodeIds);
            for (KgNode node : nodes) {
                nodeMap.put(node.getId(), node);
            }
        }

        List<String> weakPoints = weakMasteries.stream()
            .map(m -> {
                KgNode node = nodeMap.get(m.getNodeId());
                if (node == null) {
                    return null;
                }
                return node.getName() + "（掌握度 " + m.getMasteryScore().intValue() + "）";
            })
            .filter(s -> s != null)
            .collect(Collectors.toList());

        // 3. 学习路径（错误不中断对话）
        LearningPathVO path = null;
        try {
            path = pathService.getCurrent(studentId, courseId);
        } catch (Exception e) {
            // 路径不存在或查询失败，继续对话
        }

        // 3.5 诊断根因（错误不中断对话）：让伴侣知道"卡点的根本原因可能是前置点"这条因果结构
        DiagnosticResultVO diagnostic = null;
        try {
            diagnostic = diagnosticResultService.getResult(studentId, courseId);
        } catch (Exception e) {
            // 诊断数据不存在或查询失败，继续对话
        }

        LearningPathVO.NodeRef targetNode = path != null ? path.getTargetNode() : null;
        LearningPathVO.StepVO currentStep = null;
        if (path != null && path.getSteps() != null) {
            currentStep = path.getSteps().stream()
                .filter(s -> s.getStatus() == 0)
                .findFirst()
                .orElse(null);
        }

        // 4. 焦点节点
        String focusNodeCode = nodeCode;
        String focusNodeName = null;
        if (focusNodeCode != null) {
            KgNode focusNode = nodeMapper.selectOne(
                new LambdaQueryWrapper<KgNode>()
                    .eq(KgNode::getCourseId, courseId)
                    .eq(KgNode::getNodeCode, focusNodeCode)
            );
            if (focusNode != null) {
                focusNodeName = focusNode.getName();
            }
        }

        return buildSystemPrompt(whitelist, weakPoints, targetNode, currentStep,
                focusNodeCode, focusNodeName, diagnostic);
    }

    /**
     * 静态方法：构建系统提示（可测试）。diagnostic 为可空的诊断回溯结果，存在非自身根因时注入"诊断根因"段。
     */
    public static String buildSystemPrompt(
        List<String> whitelist,
        List<String> weakPoints,
        LearningPathVO.NodeRef targetNode,
        LearningPathVO.StepVO currentStep,
        String focusNodeCode,
        String focusNodeName,
        DiagnosticResultVO diagnostic
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("你是「问津」的 AI 学习伴侣，专注于帮助学生理解和掌握《软件工程》课程的知识点。\n\n");

        // 诊断根因（产品灵魂）：仅当有薄弱点且根因为前置点（非卡点自身）时注入，放在最前以提示优先级
        appendRootCauseSection(sb, diagnostic);

        // 白名单
        sb.append("## 课程知识点范围\n");
        if (!whitelist.isEmpty()) {
            for (String nodeName : whitelist) {
                sb.append("- ").append(nodeName).append("\n");
            }
        }
        sb.append("\n");

        // 薄弱点
        if (!weakPoints.isEmpty()) {
            sb.append("## 学生薄弱点\n");
            for (String weak : weakPoints) {
                sb.append("- ").append(weak).append("\n");
            }
            sb.append("\n");
        }

        // 学习路径
        if (targetNode != null) {
            sb.append("## 当前学习路径\n");
            sb.append("目标节点：").append(targetNode.getName()).append("（").append(targetNode.getNodeCode()).append("）\n");
            if (currentStep != null) {
                sb.append("当前步骤：").append(currentStep.getName()).append("（").append(currentStep.getNodeCode()).append("）\n");
            }
            sb.append("\n");
        }

        // 焦点节点
        if (focusNodeCode != null && focusNodeName != null) {
            sb.append("## 当前焦点节点\n");
            sb.append("学生正在学习：").append(focusNodeName).append("（").append(focusNodeCode).append("）\n");
            sb.append("\n");
        }

        // 引导：启发式教学 + 聊天气泡风格
        sb.append("## 对话规则\n");
        sb.append("- 只回答《软件工程》课程范围内的问题；超出范围时礼貌地把话题引导回当前焦点节点或学习路径目标。\n");
        sb.append("- 采用启发式教学：不要一次性给出完整答案。先一句话点出关键，再反问一个小问题确认理解；"
                + "学生卡住时再分步提示，逐步逼近答案。\n");
        sb.append("- 关注优先级：诊断根因 > 学生薄弱点 > 当前学习路径。\n");
        sb.append("- 回答控制在 3–5 句、口语化，适合聊天气泡；多用具体例子，少长篇理论。\n");
        sb.append("- 不要编造学生未学内容的掌握情况，只依据上文提供的诊断/掌握度信息。\n");

        return sb.toString();
    }

    /**
     * 注入"诊断根因"段：仅当存在薄弱点且根因为前置点（非卡点自身）时，
     * 让伴侣理解"卡在 X 的根因是前置点 Y"这条因果结构，而非看到一堆并列薄弱点。
     */
    private static void appendRootCauseSection(StringBuilder sb, DiagnosticResultVO diagnostic) {
        if (diagnostic == null || !diagnostic.isHasWeakness()) {
            return;
        }
        DiagnosticResultVO.RootCause rc = diagnostic.getRootCause();
        DiagnosticResultVO.NodeRef stuck = diagnostic.getStuckNode();
        if (rc == null || stuck == null || rc.isSelf()) {
            return;  // 无根因或根因即卡点本身（无前置因果结构），不注入
        }
        sb.append("## 诊断根因（最重要）\n");
        sb.append("该学生最近一次诊断显示：当前卡点是「").append(stuck.getName())
          .append("」，但根本原因更可能是前置点「").append(rc.getName())
          .append("」掌握薄弱（掌握度 ").append(fmtScore(rc.getMasteryScore())).append("）。\n");
        if (diagnostic.getChain() != null && !diagnostic.getChain().isEmpty()) {
            StringBuilder chainStr = new StringBuilder();
            for (DiagnosticResultVO.ChainNode cn : diagnostic.getChain()) {
                if (chainStr.length() > 0) {
                    chainStr.append(" → ");
                }
                chainStr.append(cn.getName());
            }
            sb.append("回溯链：").append(chainStr).append("。\n");
        }
        sb.append("当学生问到卡点或其下游内容时，优先提醒「问题可能出在更前面的 ").append(rc.getName())
          .append("」，引导先补 ").append(rc.getName()).append("，而不是直接讲卡点本身。\n\n");
    }

    /** 掌握度格式化：null → "暂无"，否则四舍五入为整数。 */
    private static String fmtScore(Double score) {
        return score == null ? "暂无" : String.valueOf(Math.round(score));
    }
}
