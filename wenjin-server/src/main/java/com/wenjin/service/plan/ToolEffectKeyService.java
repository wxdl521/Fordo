package com.wenjin.service.plan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.ToolEffectKey;
import com.wenjin.mapper.ToolEffectKeyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 写类工具幂等键登记（M10 骨架）。
 * <p>key 约定 {@code run:{runId}:step:{seq}}。重复登记返回 false（幂等跳过）。
 * {@code start_practice} 已具会话级幂等，调用方应豁免登记。
 */
@Service
public class ToolEffectKeyService {

    private static final Logger log = LoggerFactory.getLogger(ToolEffectKeyService.class);

    private final ToolEffectKeyMapper mapper;
    private final Clock clock;

    public ToolEffectKeyService(ToolEffectKeyMapper mapper, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public static String keyFor(long runId, int stepSeq) {
        return "run:" + runId + ":step:" + stepSeq;
    }

    /**
     * @return true=首次登记成功；false=已存在（应跳过副作用）
     */
    public boolean tryRegister(String toolName, String requestKey) {
        if (toolName == null || toolName.isBlank() || requestKey == null || requestKey.isBlank()) {
            return true;
        }
        try {
            ToolEffectKey row = new ToolEffectKey();
            row.setToolName(toolName.trim());
            row.setRequestKey(requestKey.trim());
            row.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()));
            mapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("tool_effect_key hit: tool={} key={}", toolName, requestKey);
            return false;
        } catch (Exception e) {
            // unique violation may surface as generic DataIntegrityViolation
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("duplicate")) {
                return false;
            }
            // 探测已有行
            Long cnt = mapper.selectCount(new LambdaQueryWrapper<ToolEffectKey>()
                    .eq(ToolEffectKey::getToolName, toolName.trim())
                    .eq(ToolEffectKey::getRequestKey, requestKey.trim()));
            if (cnt != null && cnt > 0) {
                return false;
            }
            log.warn("tool_effect_key register failed tool={} key={}: {}", toolName, requestKey, e.toString());
            return true;
        }
    }
}
