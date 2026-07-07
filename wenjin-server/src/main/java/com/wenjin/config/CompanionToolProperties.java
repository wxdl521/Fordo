package com.wenjin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "wenjin.companion.tools")
public class CompanionToolProperties {

    private boolean enabled = true;

    private int maxRounds = 2;

    private int maxToolCalls = 3;

    private long timeoutMs = 5000;

    private int maxResultChars = 2000;
}
