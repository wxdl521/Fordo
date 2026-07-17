package com.wenjin.support;

/**
 * ai_job.kind 稳定取值（VARCHAR(32)）。
 */
public final class AiJobKind {

    public static final String QUESTION_GENERATE = "QUESTION_GENERATE";
    public static final String QUESTION_ANNOTATE = "QUESTION_ANNOTATE";
    public static final String QUESTION_IMPORT_EXCEL = "QUESTION_IMPORT_EXCEL";
    public static final String GRAPH_EXTRACT = "GRAPH_EXTRACT";
    public static final String GRAPH_IMPORT_EXCEL = "GRAPH_IMPORT_EXCEL";

    private AiJobKind() {}
}
