package com.wenjin.service;

import com.wenjin.entity.CompanionConversation;
import com.wenjin.mapper.CompanionConversationMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-1 摘要守卫回写契约：
 * <ul>
 *   <li>SQL 仅更新 summary 字段（不得触碰 node_code）</li>
 *   <li>与会话切换并发时 node_code 不被摘要回写覆盖</li>
 *   <li>watermark 不得回退（旧 throughId → 0 行）</li>
 * </ul>
 * <p>本类用与 {@link CompanionConversationMapper#updateSummaryGuarded} 同语义的内存行存
 * 验证并发契约（本地无 Docker/Testcontainers 时仍可绿）。
 * 真库行为见 {@code CompanionSummaryGuardIT}。
 */
class CompanionSummaryGuardTest {

    @Test
    @DisplayName("@Update SQL 只写 summary 字段并带 watermark 前进条件")
    void guardedSqlDoesNotTouchNodeCode() throws Exception {
        Method method = CompanionConversationMapper.class.getMethod(
                "updateSummaryGuarded", Long.class, String.class, Long.class);
        Update update = method.getAnnotation(Update.class);
        assertThat(update).isNotNull();
        String sql = String.join(" ", update.value()).toLowerCase();

        assertThat(sql).contains("summary_text");
        assertThat(sql).contains("summary_through_message_id");
        assertThat(sql).contains("updated_at");
        assertThat(sql).doesNotContain("node_code");
        assertThat(sql).contains("summary_through_message_id is null")
                .contains("summary_through_message_id <");
    }

    @Test
    @DisplayName("concurrentSwitchNotClobbered: 摘要回写前节点切换提交 → node_code 保持 B 的新值")
    void concurrentSwitchNotClobbered() throws Exception {
        InMemoryConversationStore store = new InMemoryConversationStore();
        long convId = 1L;
        store.insert(conversation(convId, "OLD_NODE", null, null));

        CountDownLatch aLoaded = new CountDownLatch(1);
        CountDownLatch bSwitched = new CountDownLatch(1);
        AtomicInteger guardedRows = new AtomicInteger(-1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<?> futureA = pool.submit(() -> {
            try {
                // 线程 A：模拟 streamReply 读到旧实体后做摘要回写
                CompanionConversation stale = store.selectById(convId);
                assertThat(stale.getNodeCode()).isEqualTo("OLD_NODE");
                aLoaded.countDown();
                await(bSwitched, "B switch");

                // 生产路径：updateSummaryGuarded —— 不得整实体 updateById(stale)
                // 若误用 updateById(stale)（带 OLD_NODE + 新 summary），会覆盖 B 的 NEW_NODE
                int rows = store.updateSummaryGuarded(convId, "compressed-history-summary", 80L);
                guardedRows.set(rows);
            } catch (Throwable t) {
                error.set(t);
                aLoaded.countDown();
            }
        });

        Future<?> futureB = pool.submit(() -> {
            try {
                await(aLoaded, "A load");
                CompanionConversation live = store.selectById(convId);
                live.setNodeCode("NEW_NODE");
                live.setUpdatedAt(LocalDateTime.now());
                // 会话切换路径：保留整实体 updateById
                store.updateById(live);
                bSwitched.countDown();
            } catch (Throwable t) {
                error.set(t);
                bSwitched.countDown();
            }
        });

        futureA.get(10, TimeUnit.SECONDS);
        futureB.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(error.get()).as("no thread errors").isNull();
        assertThat(guardedRows.get()).isEqualTo(1);

        CompanionConversation after = store.selectById(convId);
        assertThat(after.getNodeCode())
                .as("node_code must not be clobbered by summary write")
                .isEqualTo("NEW_NODE");
        assertThat(after.getSummaryText()).isEqualTo("compressed-history-summary");
        assertThat(after.getSummaryThroughMessageId()).isEqualTo(80L);

        // 对照：旧路径整实体 updateById(stale) 会盖掉节点
        InMemoryConversationStore clobberDemo = new InMemoryConversationStore();
        clobberDemo.insert(conversation(2L, "OLD_NODE", null, null));
        CompanionConversation stale = clobberDemo.selectById(2L);
        CompanionConversation switched = clobberDemo.selectById(2L);
        switched.setNodeCode("NEW_NODE");
        clobberDemo.updateById(switched);
        stale.setSummaryText("would-clobber");
        stale.setSummaryThroughMessageId(80L);
        clobberDemo.updateById(stale);
        assertThat(clobberDemo.selectById(2L).getNodeCode())
                .as("control: full-entity updateById loses the concurrent switch")
                .isEqualTo("OLD_NODE");
    }

    @Test
    @DisplayName("watermarkNeverRegresses: 旧 throughId 回写返回 0，库内 watermark 不变")
    void watermarkNeverRegresses() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        long convId = 9L;
        store.insert(conversation(convId, "N1", "already-advanced", 100L));

        int rows = store.updateSummaryGuarded(convId, "stale-summary", 50L);
        assertThat(rows).as("stale watermark must not apply").isEqualTo(0);

        CompanionConversation after = store.selectById(convId);
        assertThat(after.getSummaryThroughMessageId()).isEqualTo(100L);
        assertThat(after.getSummaryText()).isEqualTo("already-advanced");

        int advanced = store.updateSummaryGuarded(convId, "fresh-summary", 150L);
        assertThat(advanced).isEqualTo(1);
        CompanionConversation advancedRow = store.selectById(convId);
        assertThat(advancedRow.getSummaryThroughMessageId()).isEqualTo(150L);
        assertThat(advancedRow.getSummaryText()).isEqualTo("fresh-summary");
    }

    private static CompanionConversation conversation(long id,
                                                      String nodeCode,
                                                      String summaryText,
                                                      Long throughId) {
        CompanionConversation c = new CompanionConversation();
        c.setId(id);
        c.setStudentId(1L);
        c.setCourseId(10L);
        c.setTitle("guard-test");
        c.setNodeCode(nodeCode);
        c.setSummaryText(summaryText);
        c.setSummaryThroughMessageId(throughId);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }

    private static void await(CountDownLatch latch, String label) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timeout waiting for " + label);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for " + label, e);
        }
    }

    /**
     * 内存行存：语义对齐 MyBatis-Plus updateById（非 null 字段覆盖）
     * 与 {@code updateSummaryGuarded} SQL（仅 summary 字段 + watermark 前进）。
     */
    static final class InMemoryConversationStore {
        private final Map<Long, CompanionConversation> rows = new ConcurrentHashMap<>();

        void insert(CompanionConversation c) {
            rows.put(c.getId(), copyOf(c));
        }

        CompanionConversation selectById(Long id) {
            CompanionConversation row = rows.get(id);
            return row == null ? null : copyOf(row);
        }

        /** 对齐 BaseMapper#updateById：非 null 字段覆盖整行相关列（含 node_code）。 */
        synchronized int updateById(CompanionConversation patch) {
            CompanionConversation row = rows.get(patch.getId());
            if (row == null) {
                return 0;
            }
            if (patch.getStudentId() != null) {
                row.setStudentId(patch.getStudentId());
            }
            if (patch.getCourseId() != null) {
                row.setCourseId(patch.getCourseId());
            }
            if (patch.getTitle() != null) {
                row.setTitle(patch.getTitle());
            }
            if (patch.getNodeCode() != null) {
                row.setNodeCode(patch.getNodeCode());
            }
            if (patch.getSummaryText() != null) {
                row.setSummaryText(patch.getSummaryText());
            }
            if (patch.getSummaryThroughMessageId() != null) {
                row.setSummaryThroughMessageId(patch.getSummaryThroughMessageId());
            }
            if (patch.getUpdatedAt() != null) {
                row.setUpdatedAt(patch.getUpdatedAt());
            }
            return 1;
        }

        /**
         * 对齐 mapper SQL：
         * UPDATE ... SET summary_text, summary_through_message_id, updated_at
         * WHERE id=? AND (summary_through_message_id IS NULL OR summary_through_message_id < ?)
         */
        synchronized int updateSummaryGuarded(Long id, String summaryText, Long throughId) {
            CompanionConversation row = rows.get(id);
            if (row == null) {
                return 0;
            }
            Long current = row.getSummaryThroughMessageId();
            if (current != null && current >= throughId) {
                return 0;
            }
            row.setSummaryText(summaryText);
            row.setSummaryThroughMessageId(throughId);
            row.setUpdatedAt(LocalDateTime.now());
            return 1;
        }

        private static CompanionConversation copyOf(CompanionConversation src) {
            CompanionConversation c = new CompanionConversation();
            c.setId(src.getId());
            c.setStudentId(src.getStudentId());
            c.setCourseId(src.getCourseId());
            c.setTitle(src.getTitle());
            c.setNodeCode(src.getNodeCode());
            c.setSummaryText(src.getSummaryText());
            c.setSummaryThroughMessageId(src.getSummaryThroughMessageId());
            c.setCreatedAt(src.getCreatedAt());
            c.setUpdatedAt(src.getUpdatedAt());
            return c;
        }
    }
}
