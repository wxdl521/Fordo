package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.CompanionAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.dto.CompanionConversationVO;
import com.wenjin.dto.CompanionMessageVO;
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

        return buildSystemPrompt(whitelist, weakPoints, targetNode, currentStep, focusNodeCode, focusNodeName);
    }

    /**
     * 静态方法：构建系统提示（可测试）。
     */
    public static String buildSystemPrompt(
        List<String> whitelist,
        List<String> weakPoints,
        LearningPathVO.NodeRef targetNode,
        LearningPathVO.StepVO currentStep,
        String focusNodeCode,
        String focusNodeName
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("你是一位耐心的数学学习伴侣，专注于帮助学生理解和掌握知识点。\n\n");

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

        // 引导
        sb.append("## 对话规则\n");
        sb.append("- 只回答上述课程范围内的问题。\n");
        sb.append("- 如果学生提问超出范围，礼貌引导回到课程内容。\n");
        sb.append("- 优先关注学生的薄弱点和当前学习路径。\n");
        sb.append("- 回答简洁清晰，避免过长的理论，多用例子。\n");

        return sb.toString();
    }
}
