package com.wenjin.sysconfig;

import com.wenjin.config.AiProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;

/**
 * 白名单 key 读写的目标：运行中的 {@code @ConfigurationProperties} 单例。
 * 写回这些 bean 即热生效（调用点每次现读，相关字段已标 volatile）。
 */
public record ConfigBeans(AiProperties ai, FeatureFlagsProperties features, LimitsProperties limits) {
}
