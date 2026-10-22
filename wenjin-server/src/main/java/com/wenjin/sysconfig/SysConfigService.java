package com.wenjin.sysconfig;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AiProperties;
import com.wenjin.config.CodeCheckProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.AdminConfigGroupVO;
import com.wenjin.dto.AdminConfigItemVO;
import com.wenjin.entity.SysConfig;
import com.wenjin.mapper.SysConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置覆盖层：库表 {@code sys_config} ↔ 运行中的 properties bean。
 *
 * <h2>模型</h2>
 * <ul>
 *   <li>yml / 环境变量是默认值；{@link #snapshotDefaults()} 在应用覆盖前把每个白名单 key 的默认值快照下来
 *       （只在内存里，secret 默认值也只在内存），恢复默认就是写回快照值。</li>
 *   <li>保存 / 恢复默认：先写库，成功后立即写回 bean（字段 volatile，调用点每次现读 → 热生效）。
 *       写库 + 写 bean 在同一把锁内串行，避免并发保存同一 key 时库与 bean 不一致。</li>
 *   <li>多实例部署时只有处理请求的那台立即生效，其余实例在下次重启时生效。</li>
 * </ul>
 *
 * <h2>保密</h2>
 * <p>secret 明文只出现在：请求体 → {@link ConfigCrypto#encrypt} 与 bean 字段。接口只回 configured + 掩码；
 * 日志与异常 message 不含任何配置值。</p>
 */
@Service
public class SysConfigService {

    private static final Logger log = LoggerFactory.getLogger(SysConfigService.class);

    /** 报错里回显的 key 最长长度（key 来自 URL，防止超长串进日志） */
    private static final int MAX_KEY_ECHO = 128;

    private final SysConfigMapper mapper;
    private final ConfigRegistry registry;
    private final ConfigCrypto crypto;
    private final ConfigBeans beans;

    private final Object lock = new Object();
    /** 启动时快照的默认值（key → 类型化值，可含 null）；快照后不可变。 */
    private volatile Map<String, Object> defaults;

    public SysConfigService(SysConfigMapper mapper, ConfigRegistry registry, ConfigCrypto crypto,
                            AiProperties ai, FeatureFlagsProperties features, LimitsProperties limits,
                            CodeCheckProperties codeCheck) {
        this.mapper = mapper;
        this.registry = registry;
        this.crypto = crypto;
        this.beans = new ConfigBeans(ai, features, limits, codeCheck);
    }

    // ───────────────────────── 启动 ─────────────────────────

    /** 快照每个白名单 key 的当前（= yml / 环境变量）值；幂等，只有第一次生效。 */
    public void snapshotDefaults() {
        ensureDefaults();
    }

    /**
     * 把库里的覆盖值写回 bean。任何一项失败（已不在白名单、解密失败、值已不合规）只 warn 并跳过，绝不阻断启动；
     * 读表失败（如未跑 V18 迁移）同样只 warn。
     *
     * @return 成功应用的条数
     */
    public int applyStoredOverrides() {
        ensureDefaults();
        List<SysConfig> rows;
        try {
            rows = mapper.selectList(null);
        } catch (Exception e) {
            log.warn("读取 sys_config 失败，系统配置覆盖未生效（是否已执行 V18 迁移？）: {}",
                    e.getClass().getSimpleName());
            return 0;
        }
        int applied = 0;
        synchronized (lock) {
            for (SysConfig row : rows) {
                String key = row.getConfigKey();
                ConfigKeyDef def = registry.find(key).orElse(null);
                if (def == null) {
                    log.warn("sys_config 中的 key 已不在可编辑白名单内，已忽略: {}", key);
                    continue;
                }
                try {
                    def.apply(beans, decode(def, row));
                    applied++;
                } catch (Exception e) {
                    // message 只来自 ConfigValues / ConfigCrypto，均不含配置值
                    log.warn("sys_config 覆盖值无法应用，已跳过（沿用默认值） key={} 原因={}", key, e.getMessage());
                }
            }
        }
        log.info("系统配置覆盖已应用 {} 项（库中共 {} 项）", applied, rows.size());
        return applied;
    }

    // ───────────────────────── 接口 ─────────────────────────

    /** 全部白名单项按分组返回（分组与项的顺序 = 登记顺序）。 */
    public List<AdminConfigGroupVO> list() {
        ensureDefaults();
        Map<String, SysConfig> rows = new HashMap<>();
        for (SysConfig row : mapper.selectList(null)) {
            rows.put(row.getConfigKey(), row);
        }
        Map<String, List<AdminConfigItemVO>> grouped = new LinkedHashMap<>();
        for (ConfigKeyDef def : registry.all()) {
            grouped.computeIfAbsent(def.group(), g -> new ArrayList<>()).add(toItem(def, rows.get(def.key())));
        }
        List<AdminConfigGroupVO> out = new ArrayList<>(grouped.size());
        grouped.forEach((group, items) -> out.add(new AdminConfigGroupVO(group, items)));
        return out;
    }

    /**
     * 保存覆盖值并立即生效。
     *
     * @throws BusinessException 400：key 不在白名单 / 值不合规
     */
    public AdminConfigItemVO update(String key, Object rawValue, Long operatorId) {
        ConfigKeyDef def = requireDef(key);
        Object value = ConfigValues.parse(def, rawValue);
        ensureDefaults();
        synchronized (lock) {
            if (def.isSecret()) {
                mapper.upsert(def.key(), crypto.encrypt(def.key(), (String) value), 1, operatorId);
            } else {
                mapper.upsert(def.key(), ConfigValues.serialize(def, value), 0, operatorId);
            }
            def.apply(beans, value);
        }
        return toItem(def, mapper.selectById(def.key()));
    }

    /**
     * 删除覆盖值，bean 恢复为启动时快照的默认值（无覆盖时等同无操作）。
     *
     * @throws BusinessException 400：key 不在白名单
     */
    public AdminConfigItemVO reset(String key) {
        ConfigKeyDef def = requireDef(key);
        Map<String, Object> d = ensureDefaults();
        synchronized (lock) {
            mapper.deleteById(def.key());
            def.apply(beans, d.get(def.key()));
        }
        return toItem(def, null);
    }

    // ───────────────────────── 内部 ─────────────────────────

    private ConfigKeyDef requireDef(String key) {
        return registry.find(key).orElseThrow(() -> new BusinessException(ResultCode.BAD_REQUEST,
                "不支持的配置项" + (key != null && key.length() <= MAX_KEY_ECHO ? "：" + key : "")));
    }

    private Object decode(ConfigKeyDef def, SysConfig row) {
        String stored = row.getConfigValue();
        boolean encrypted = row.getEncrypted() != null && row.getEncrypted() == 1;
        if (def.isSecret()) {
            if (!encrypted) {
                throw new IllegalStateException("secret 项未加密存储，拒绝使用");
            }
            return ConfigValues.parse(def, crypto.decrypt(def.key(), stored));
        }
        if (encrypted) {
            throw new IllegalStateException("非 secret 项不应为密文");
        }
        return ConfigValues.parse(def, stored);
    }

    private Map<String, Object> ensureDefaults() {
        Map<String, Object> d = defaults;
        if (d != null) {
            return d;
        }
        synchronized (lock) {
            if (defaults == null) {
                Map<String, Object> snapshot = new HashMap<>();
                for (ConfigKeyDef def : registry.all()) {
                    snapshot.put(def.key(), def.read(beans));
                }
                defaults = Collections.unmodifiableMap(snapshot);
            }
            return defaults;
        }
    }

    private AdminConfigItemVO toItem(ConfigKeyDef def, SysConfig row) {
        AdminConfigItemVO vo = new AdminConfigItemVO();
        vo.setKey(def.key());
        vo.setLabel(def.label());
        vo.setDescription(def.description());
        vo.setType(def.type().wire());
        Object current = def.read(beans);
        if (def.isSecret()) {
            String plain = (String) current;
            vo.setConfigured(plain != null && !plain.isBlank());
            vo.setMasked(ConfigValues.mask(plain));
        } else {
            vo.setValue(current);
            vo.setDefaultValue(ensureDefaults().get(def.key()));
        }
        switch (def.type()) {
            case NUMBER -> {
                vo.setMin(def.min());
                vo.setMax(def.max());
                vo.setNullable(def.nullable() ? Boolean.TRUE : null);
            }
            case ENUM -> vo.setOptions(def.options());
            default -> { }
        }
        vo.setOverridden(row != null);
        if (row != null) {
            vo.setUpdatedAt(row.getUpdatedAt() == null ? null
                    : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(row.getUpdatedAt()));
            vo.setUpdatedBy(row.getUpdatedBy());
        }
        return vo;
    }
}
