package com.wenjin.eval.integration;

import com.wenjin.eval.fixture.EvalWorld;
import com.wenjin.eval.runner.EvalHarness;
import com.wenjin.service.rag.DocIngestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I3 真库验证：DOC_INGEST 作业终态 FAILED/CANCELLED 而来源仍 INGESTING 时，{@code failOrphanedIngestingSources}
 * 把来源置 FAILED；作业仍 PENDING/RUNNING、或作业成功、或 sourceId 前缀相同（7 vs 77）的来源不受影响。
 * Gated by {@code WENJIN_EVAL_IT=true}。
 */
@EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_IT", matches = "true")
class DocIngestOrphanSweepIT extends EvalDbSupport {

    @Autowired private EvalHarness harness;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DocIngestService docIngestService;

    private EvalWorld world;

    @BeforeEach
    void setUp() {
        world = harness.fixture().world();
        cleanup();
    }

    @AfterEach
    void cleanup() {
        if (world == null) {
            return;
        }
        jdbc.update("DELETE FROM ai_job WHERE request_key LIKE 'sweep-it:%'");
        jdbc.update("DELETE FROM doc_source WHERE title LIKE 'sweep-it:%'");
    }

    private long source(String title, int status) {
        jdbc.update("INSERT INTO doc_source (course_id, title, kind, file_hash, status, created_by, created_at)"
                        + " VALUES (?, ?, 0, ?, ?, ?, NOW())",
                world.courseId(), "sweep-it:" + title, "sweep-it-" + title, status, world.teacherId());
        return jdbc.queryForObject("SELECT id FROM doc_source WHERE title = ?", Long.class, "sweep-it:" + title);
    }

    private void job(String key, long sourceId, int status) {
        jdbc.update("INSERT INTO ai_job (kind, owner_id, course_id, request_key, status, request_json)"
                        + " VALUES ('DOC_INGEST', ?, ?, ?, ?, ?)",
                world.teacherId(), world.courseId(), "sweep-it:" + key, status,
                "{\"fileName\":\"a.pdf\",\"sourceId\":" + sourceId + ",\"fileHash\":\"h\"}");
    }

    private int statusOf(long sourceId) {
        return jdbc.queryForObject("SELECT status FROM doc_source WHERE id = ?", Integer.class, sourceId);
    }

    @Test
    @DisplayName("终态 FAILED/CANCELLED 作业的 INGESTING 来源被置 FAILED；其余不动")
    void sweepsOnlyOrphans() {
        long failedJob = source("failed-job", 0);
        job("a", failedJob, 3);
        long cancelledJob = source("cancelled-job", 0);
        job("b", cancelledJob, 4);
        long runningJob = source("running-job", 0);
        job("c", runningJob, 1);
        long retriedJob = source("failed-then-retried", 0);
        job("d1", retriedJob, 3);
        job("d2", retriedJob, 0); // 重试作业仍在排队 → 不能误杀
        long okJob = source("succeeded-job", 0);
        job("e", okJob, 2);
        long readySource = source("ready-source", 1);
        job("f", readySource, 3); // 已 READY 的来源不被改回
        // 前缀碰撞：作业的 sourceId=failedJob，另一来源 id 为其 10 倍之类的不应被匹配
        long other = source("prefix-other", 0);
        job("g", Long.parseLong(other + "0"), 3);

        int n = docIngestService.failOrphanedIngestingSources();

        assertThat(n).isEqualTo(2);
        assertThat(statusOf(failedJob)).isEqualTo(2);
        assertThat(statusOf(cancelledJob)).isEqualTo(2);
        assertThat(statusOf(runningJob)).isEqualTo(0);
        assertThat(statusOf(retriedJob)).isEqualTo(0);
        assertThat(statusOf(okJob)).isEqualTo(0);
        assertThat(statusOf(readySource)).isEqualTo(1);
        assertThat(statusOf(other)).isEqualTo(0);
        assertThat(docIngestService.failOrphanedIngestingSources()).isZero(); // 幂等
    }
}
