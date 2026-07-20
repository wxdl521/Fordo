package com.wenjin.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.ai.MemoryDistillAiClient;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.entity.CompanionMessage;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.CompanionConversationMapper;
import com.wenjin.mapper.CompanionMessageMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.support.MemoryKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * MEMORY_DISTILL 作业执行体：LLM 蒸馏 → 夹紧 → upsert/reinforce → 推进 distilled watermark。
 */
@Service
public class MemoryDistillService {

    private static final Logger log = LoggerFactory.getLogger(MemoryDistillService.class);

    public static final int MAX_EPISODIC = 3;
    public static final int MAX_SEMANTIC = 2;
    public static final int MAX_CONTENT_CHARS = 200;

    private final CompanionConversationMapper conversationMapper;
    private final CompanionMessageMapper messageMapper;
    private final KgNodeMapper nodeMapper;
    private final MemoryDistillAiClient distillAiClient;
    private final StudentMemoryService studentMemoryService;

    public MemoryDistillService(CompanionConversationMapper conversationMapper,
                                CompanionMessageMapper messageMapper,
                                KgNodeMapper nodeMapper,
                                MemoryDistillAiClient distillAiClient,
                                StudentMemoryService studentMemoryService) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.nodeMapper = Objects.requireNonNull(nodeMapper);
        this.distillAiClient = Objects.requireNonNull(distillAiClient);
        this.studentMemoryService = Objects.requireNonNull(studentMemoryService);
    }

    /**
     * @param conversationId 会话
     * @param throughId      本次蒸馏上界（含）；对应 summary_through 推进点
     * @return 结果摘要 map（写入 ai_job.result_json）
     */
    @Transactional
    public Map<String, Object> distill(Long conversationId, Long throughId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversationId);
        result.put("throughId", throughId);
        result.put("insertedOrReinforced", 0);
        result.put("watermarkAdvanced", false);

        if (conversationId == null || throughId == null) {
            result.put("skipped", "missing_ids");
            return result;
        }

        CompanionConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            result.put("skipped", "conversation_not_found");
            return result;
        }

        Long distilled = conv.getDistilledThroughMessageId();
        if (distilled != null && distilled >= throughId) {
            result.put("skipped", "already_distilled");
            return result;
        }

        List<CompanionMessage> window = loadWindow(conversationId, distilled, throughId);
        Set<String> whitelist = loadNodeWhitelist(conv.getCourseId());
        List<MemoryDistillAiClient.DistilledItem> raw =
                distillAiClient.distill(window == null ? List.of() : window, whitelist);
        List<MemoryDistillAiClient.DistilledItem> clamped = clamp(raw, whitelist);

        int applied = 0;
        for (MemoryDistillAiClient.DistilledItem item : clamped) {
            Long id = studentMemoryService.upsertOrReinforce(
                    conv.getStudentId(),
                    conv.getCourseId(),
                    item.kind(),
                    item.content(),
                    item.nodeCode(),
                    null);
            if (id != null) {
                applied++;
            }
        }
        result.put("insertedOrReinforced", applied);
        result.put("candidates", clamped.size());

        int rows = conversationMapper.updateDistilledThroughGuarded(conversationId, throughId);
        result.put("watermarkAdvanced", rows > 0);
        if (rows == 0) {
            log.debug("distilled watermark not advanced: conversationId={} throughId={}",
                    conversationId, throughId);
        }
        return result;
    }

    static List<MemoryDistillAiClient.DistilledItem> clamp(
            List<MemoryDistillAiClient.DistilledItem> raw,
            Set<String> whitelist) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        int episodic = 0;
        int semantic = 0;
        List<MemoryDistillAiClient.DistilledItem> out = new ArrayList<>();
        for (MemoryDistillAiClient.DistilledItem item : raw) {
            if (item == null || !StringUtils.hasText(item.content())) {
                continue;
            }
            int kind = item.kind();
            if (kind == MemoryKind.EPISODIC) {
                if (episodic >= MAX_EPISODIC) {
                    continue;
                }
                episodic++;
            } else if (kind == MemoryKind.SEMANTIC) {
                if (semantic >= MAX_SEMANTIC) {
                    continue;
                }
                semantic++;
            } else if (kind == MemoryKind.PROCEDURAL) {
                // 蒸馏配额只保证 episodic/semantic；procedural 暂不产出上限内保留
            } else {
                continue;
            }
            String content = item.content().trim();
            if (content.length() > MAX_CONTENT_CHARS) {
                content = content.substring(0, MAX_CONTENT_CHARS);
            }
            String node = item.nodeCode();
            if (StringUtils.hasText(node)) {
                node = node.trim();
                if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(node)) {
                    node = null;
                }
            } else {
                node = null;
            }
            out.add(new MemoryDistillAiClient.DistilledItem(kind, content, node));
        }
        return out;
    }

    private List<CompanionMessage> loadWindow(Long conversationId, Long afterId, Long throughId) {
        List<CompanionMessage> since = messageMapper.selectSinceIdAsc(conversationId, afterId);
        if (since == null || since.isEmpty()) {
            return List.of();
        }
        List<CompanionMessage> window = new ArrayList<>();
        for (CompanionMessage m : since) {
            if (m.getId() != null && m.getId() <= throughId) {
                window.add(m);
            }
        }
        return window;
    }

    private Set<String> loadNodeWhitelist(Long courseId) {
        if (courseId == null) {
            return Set.of();
        }
        // QueryWrapper 列名字符串，避免单元测试无 MyBatis-Plus lambda 缓存
        List<KgNode> nodes = nodeMapper.selectList(new QueryWrapper<KgNode>()
                .eq("course_id", courseId)
                .select("node_code"));
        if (nodes == null || nodes.isEmpty()) {
            return Set.of();
        }
        Set<String> set = new HashSet<>();
        for (KgNode n : nodes) {
            if (n != null && StringUtils.hasText(n.getNodeCode())) {
                set.add(n.getNodeCode().trim());
            }
        }
        return set;
    }

    /** 从 job request map 解析 long。 */
    public static long longVal(Map<String, Object> request, String key, long defaultValue) {
        if (request == null) {
            return defaultValue;
        }
        Object v = request.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static Map<String, Object> requestBody(long conversationId, long throughId,
                                                  long studentId, long courseId) {
        Map<String, Object> body = new HashMap<>();
        body.put("conversationId", conversationId);
        body.put("throughId", throughId);
        body.put("studentId", studentId);
        body.put("courseId", courseId);
        return body;
    }

    public static String requestKey(long conversationId, long throughId) {
        return "conv:" + conversationId + ":through:" + throughId;
    }
}
