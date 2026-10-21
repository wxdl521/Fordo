package com.wenjin.sysconfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动钩子：应用就绪时先快照白名单 key 的默认值，再把 {@code sys_config} 覆盖值写回 properties bean。
 * 任何失败只记日志，绝不阻断启动（覆盖层是增强项，缺了它系统按 yml 默认值照常工作）。
 */
@Component
public class SysConfigApplier {

    private static final Logger log = LoggerFactory.getLogger(SysConfigApplier.class);

    private final SysConfigService service;

    public SysConfigApplier(SysConfigService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            service.snapshotDefaults();
            service.applyStoredOverrides();
        } catch (Exception e) {
            log.error("系统配置覆盖初始化失败，沿用 yml 默认值: {}", e.getClass().getSimpleName());
        }
    }
}
