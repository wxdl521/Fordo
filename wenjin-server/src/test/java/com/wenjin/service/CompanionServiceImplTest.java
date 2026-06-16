package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.CompanionAiClient;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CompanionServiceImpl 测试（TDD 4 例）。
 */
@ExtendWith(MockitoExtension.class)
class CompanionServiceImplTest {

    @Mock
    private CompanionConversationMapper conversationMapper;

    @Mock
    private CompanionMessageMapper messageMapper;

    @Mock
    private KgNodeMapper nodeMapper;

    @Mock
    private StudentMasteryMapper masteryMapper;

    @Mock
    private PathService pathService;

    @Mock
    private CompanionAiClient aiClient;

    @InjectMocks
    private CompanionServiceImpl service;

    private CompanionChatRequest req;

    @BeforeEach
    void setUp() {
        req = new CompanionChatRequest();
        req.setStudentId(1L);
        req.setCourseId(10L);
        req.setMessage("什么是二次函数？");
    }

    /**
     * 测试 1：首次对话时创建新会话并保存用户消息。
     */
    @Test
    void startTurnCreatesConversation() {
        req.setConversationId(null);  // 首次对话
        req.setNodeCode("KT12");

        // Mock：节点查询
        KgNode node = new KgNode();
        node.setNodeCode("KT12");
        node.setName("二次函数基础");
        when(nodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(node);

        // Mock：会话插入
        doAnswer(inv -> {
            CompanionConversation conv = inv.getArgument(0);
            conv.setId(100L);  // 模拟数据库生成的 ID
            return 1;
        }).when(conversationMapper).insert(any(CompanionConversation.class));

        // Mock：消息插入
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        Long convId = service.startTurn(req);

        assertEquals(100L, convId);

        // 验证会话创建
        ArgumentCaptor<CompanionConversation> convCaptor = ArgumentCaptor.forClass(CompanionConversation.class);
        verify(conversationMapper).insert(convCaptor.capture());
        CompanionConversation savedConv = convCaptor.getValue();
        assertEquals(1L, savedConv.getStudentId());
        assertEquals(10L, savedConv.getCourseId());
        assertEquals("KT12", savedConv.getNodeCode());
        assertTrue(savedConv.getTitle().contains("二次函数基础"));
        assertTrue(savedConv.getTitle().contains("什么是二次函数"));

        // 验证用户消息保存
        ArgumentCaptor<CompanionMessage> msgCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(msgCaptor.capture());
        CompanionMessage savedMsg = msgCaptor.getValue();
        assertEquals(100L, savedMsg.getConversationId());
        assertEquals(1, savedMsg.getRole());  // user
        assertEquals("什么是二次函数？", savedMsg.getContent());
    }

    /**
     * 测试 2：后续对话时复用会话，仅保存用户消息。
     */
    @Test
    void startTurnReusesConversation() {
        req.setConversationId(200L);  // 复用会话

        // Mock：会话存在
        CompanionConversation existingConv = new CompanionConversation();
        existingConv.setId(200L);
        existingConv.setStudentId(1L);
        existingConv.setCourseId(10L);
        when(conversationMapper.selectById(200L)).thenReturn(existingConv);

        // Mock：消息插入
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        // Mock：会话更新
        when(conversationMapper.updateById(any(CompanionConversation.class))).thenReturn(1);

        Long convId = service.startTurn(req);

        assertEquals(200L, convId);

        // 验证不再创建新会话
        verify(conversationMapper, never()).insert(any(CompanionConversation.class));

        // 验证更新会话的 updatedAt
        verify(conversationMapper).updateById(any(CompanionConversation.class));

        // 验证用户消息保存
        ArgumentCaptor<CompanionMessage> msgCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper).insert(msgCaptor.capture());
        CompanionMessage savedMsg = msgCaptor.getValue();
        assertEquals(200L, savedMsg.getConversationId());
        assertEquals(1, savedMsg.getRole());  // user
    }

    /**
     * 测试 3：buildSystemPrompt 方法正确构建系统提示（静态方法，无 mock）。
     */
    @Test
    void buildPromptIncludesWhitelistWeakPathAndFocus() {
        List<String> whitelist = Arrays.asList("二次函数基础", "一元二次方程", "函数图像");
        List<String> weakPoints = Arrays.asList("函数图像（掌握度 45）", "配方法（掌握度 38）");

        LearningPathVO.NodeRef targetNode = new LearningPathVO.NodeRef();
        targetNode.setNodeCode("KT20");
        targetNode.setName("一元二次方程");

        LearningPathVO.StepVO currentStep = new LearningPathVO.StepVO();
        currentStep.setNodeCode("KT12");
        currentStep.setName("二次函数基础");

        String prompt = CompanionServiceImpl.buildSystemPrompt(
            whitelist, weakPoints, targetNode, currentStep, "KT12", "二次函数基础"
        );

        // 验证包含白名单
        assertTrue(prompt.contains("二次函数基础"));
        assertTrue(prompt.contains("一元二次方程"));
        assertTrue(prompt.contains("函数图像"));

        // 验证包含薄弱点
        assertTrue(prompt.contains("函数图像（掌握度 45）"));
        assertTrue(prompt.contains("配方法（掌握度 38）"));

        // 验证包含路径信息
        assertTrue(prompt.contains("一元二次方程"));  // 目标节点
        assertTrue(prompt.contains("二次函数基础"));  // 当前步骤

        // 验证包含焦点节点
        assertTrue(prompt.contains("KT12"));
        assertTrue(prompt.contains("二次函数基础"));
    }

    /**
     * 测试 4：streamReply 调用 AI 客户端并累积回复后落库。
     */
    @Test
    void streamReplyAccumulatesAndPersists() {
        Long conversationId = 300L;

        // Mock：会话查询
        CompanionConversation conv = new CompanionConversation();
        conv.setId(300L);
        conv.setStudentId(1L);
        conv.setCourseId(10L);
        conv.setNodeCode("KT12");
        when(conversationMapper.selectById(300L)).thenReturn(conv);

        // Mock：历史消息查询
        CompanionMessage historyMsg = new CompanionMessage();
        historyMsg.setRole(1);  // user
        historyMsg.setContent("什么是二次函数？");
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(historyMsg));

        // Mock：节点白名单查询（课程下所有节点）
        KgNode node1 = new KgNode();
        node1.setName("二次函数基础");
        KgNode node2 = new KgNode();
        node2.setName("一元二次方程");
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(node1, node2));

        // Mock：薄弱点查询
        StudentMastery mastery1 = new StudentMastery();
        mastery1.setNodeId(12L);
        mastery1.setMasteryScore(new BigDecimal("45.0"));
        StudentMastery mastery2 = new StudentMastery();
        mastery2.setNodeId(15L);
        mastery2.setMasteryScore(new BigDecimal("38.0"));
        when(masteryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(mastery1, mastery2));

        // Mock：节点名查询（薄弱点批量查询）
        KgNode weakNode1 = new KgNode();
        weakNode1.setId(12L);
        weakNode1.setName("函数图像");
        KgNode weakNode2 = new KgNode();
        weakNode2.setId(15L);
        weakNode2.setName("配方法");
        when(nodeMapper.selectBatchIds(Arrays.asList(12L, 15L))).thenReturn(Arrays.asList(weakNode1, weakNode2));

        // Mock：学习路径查询
        LearningPathVO pathVO = new LearningPathVO();
        LearningPathVO.NodeRef targetNode = new LearningPathVO.NodeRef();
        targetNode.setNodeCode("KT20");
        targetNode.setName("一元二次方程");
        pathVO.setTargetNode(targetNode);
        LearningPathVO.StepVO step = new LearningPathVO.StepVO();
        step.setNodeCode("KT12");
        step.setName("二次函数基础");
        pathVO.setSteps(Arrays.asList(step));
        when(pathService.getCurrent(1L, 10L)).thenReturn(pathVO);

        // Mock：焦点节点查询
        KgNode focusNode = new KgNode();
        focusNode.setName("二次函数基础");
        when(nodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(focusNode);

        // Mock：AI 客户端流式回调
        doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(3);
            onToken.accept("二次");
            onToken.accept("函数");
            onToken.accept("是");
            onToken.accept("一种");
            onToken.accept("重要");
            onToken.accept("的");
            onToken.accept("数学");
            onToken.accept("模型");
            return null;
        }).when(aiClient).stream(any(), any(), any(), any());

        // Mock：AI 消息插入
        when(messageMapper.insert(any(CompanionMessage.class))).thenReturn(1);

        // 执行流式回复
        StringBuilder accumulated = new StringBuilder();
        service.streamReply(conversationId, token -> accumulated.append(token));

        // 验证累积内容
        assertEquals("二次函数是一种重要的数学模型", accumulated.toString());

        // 验证 AI 客户端被调用
        verify(aiClient).stream(any(), any(), any(), any());

        // 验证 AI 回复落库
        ArgumentCaptor<CompanionMessage> msgCaptor = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageMapper, times(1)).insert(msgCaptor.capture());
        CompanionMessage savedMsg = msgCaptor.getValue();
        assertEquals(300L, savedMsg.getConversationId());
        assertEquals(2, savedMsg.getRole());  // ai
        assertEquals("二次函数是一种重要的数学模型", savedMsg.getContent());
    }
}
