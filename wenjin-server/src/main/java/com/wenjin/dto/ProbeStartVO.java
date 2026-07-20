package com.wenjin.dto;

import lombok.Data;

/**
 * One-shot probe_understanding card (M9). Desensitized question only — no correct key.
 */
@Data
public class ProbeStartVO {

    private String sessionId;
    private String nodeCode;
    private String nodeName;
    /** BANK or EPHEMERAL */
    private String source;
    private boolean ephemeral;
    private PaperQuestionVO question;
}
