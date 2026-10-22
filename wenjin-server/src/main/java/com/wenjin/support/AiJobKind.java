package com.wenjin.support;

/**
 * ai_job.kind 稳定取值（VARCHAR(32)）。
 * <p>
 * M7 选择 {@link #SUMMARY_REFRESH}（而非合并进 CONV_DIGEST）：请求路径保持确定性
 * mergeSummary；异步 LLM 润色走独立 kind，便于幂等键、观测与回退隔离。
 */
public final class AiJobKind {

    public static final String QUESTION_GENERATE = "QUESTION_GENERATE";
    public static final String QUESTION_ANNOTATE = "QUESTION_ANNOTATE";
    public static final String QUESTION_IMPORT_EXCEL = "QUESTION_IMPORT_EXCEL";
    public static final String GRAPH_EXTRACT = "GRAPH_EXTRACT";
    public static final String GRAPH_IMPORT_EXCEL = "GRAPH_IMPORT_EXCEL";

    /**
     * 会话摘要更新后蒸馏学生长期记忆（owner=studentId；
     * request_key={@code conv:{id}:through:{toId}}）。
     */
    public static final String MEMORY_DISTILL = "MEMORY_DISTILL";

    /**
     * 异步 LLM 润色会话摘要（等式守卫：summary_through_message_id 必须仍等于派发时 through）。
     * request_key={@code conv:{id}:summary:{throughId}}。
     */
    public static final String SUMMARY_REFRESH = "SUMMARY_REFRESH";

    /**
     * 语料入库（M8）：NODE_REF Markdown / 课件 PDF·PPT。
     * request_key={@code src:{courseId}:{fileHash}}；request_json 含 sourceId、fileHash。
     */
    public static final String DOC_INGEST = "DOC_INGEST";

    /**
     * 语料切块向量化（R2）：owner=来源创建教师 / 发起重建的教师。
     * request_key={@code emb:{sourceId}:{modelId}:{minChunkId}}（入库后自动）或
     * {@code emb:{sourceId}:{modelId}:r{epochMinute}}（重建向量）；request_json 含 sourceId、model。
     */
    public static final String RAG_EMBED = "RAG_EMBED";

    /**
     * 教师学情周报（M11 可选）。
     * request_key={@code course:{courseId}:period:{periodStart}}。
     */
    public static final String TEACHER_WEEKLY_REPORT = "TEACHER_WEEKLY_REPORT";

    /**
     * 伴学质量抽查（M11 可选）。
     * request_key={@code course:{courseId}:period:{periodStart}}。
     */
    public static final String SUPERVISOR_SAMPLE = "SUPERVISOR_SAMPLE";

    private AiJobKind() {}

    /** 伴侣侧学生归属异步作业（允许 student actor）。 */
    public static boolean isStudentSideJob(String kind) {
        return MEMORY_DISTILL.equals(kind) || SUMMARY_REFRESH.equals(kind);
    }

    /** 多智能体系统作业（owner=课程教师；允许 teacher/admin actor）。 */
    public static boolean isMultiAgentJob(String kind) {
        return TEACHER_WEEKLY_REPORT.equals(kind) || SUPERVISOR_SAMPLE.equals(kind);
    }
}
