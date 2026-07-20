package com.wenjin.dto;

import lombok.Data;

/**
 * Probe submit result (M9). Never carries mastery deltas — EWMA isolation.
 */
@Data
public class ProbeSubmitVO {

    private String sessionId;
    private boolean correct;
    /** True when incorrect — client may request re-explain with a new strategy. */
    private boolean reexplainAvailable;
    private String nodeCode;
    private boolean ephemeral;
}
