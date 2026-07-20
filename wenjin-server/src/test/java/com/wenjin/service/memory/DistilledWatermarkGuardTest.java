package com.wenjin.service.memory;

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
 * T8：distilled_through_message_id 守卫并发契约（对齐 P0-1）。
 * 本地无 Docker 的内存行存验证；真库行为可另挂 IT。
 */
class DistilledWatermarkGuardTest {

    @Test
    @DisplayName("@Update SQL 仅推进 distilled_through 且带严格前进条件")
    void guardedSqlOnlyAdvancesDistilledThrough() throws Exception {
        Method method = CompanionConversationMapper.class.getMethod(
                "updateDistilledThroughGuarded", Long.class, Long.class);
        Update update = method.getAnnotation(Update.class);
        assertThat(update).isNotNull();
        String sql = String.join(" ", update.value()).toLowerCase();

        assertThat(sql).contains("distilled_through_message_id");
        assertThat(sql).contains("updated_at");
        assertThat(sql).doesNotContain("summary_text");
        assertThat(sql).doesNotContain("node_code");
        assertThat(sql).contains("distilled_through_message_id is null")
                .contains("distilled_through_message_id <");
    }

    @Test
    @DisplayName("并发：旧 through 不得回退；仅较大 through 生效")
    void concurrentAdvance_noRegression() throws Exception {
        InMemoryStore store = new InMemoryStore();
        long convId = 1L;
        store.insert(conversation(convId, null));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger rowsLow = new AtomicInteger(-1);
        AtomicInteger rowsHigh = new AtomicInteger(-1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<?> fLow = pool.submit(() -> {
            try {
                ready.countDown();
                await(go, "go");
                // 故意稍后写入较小 through，模拟乱序完成
                Thread.sleep(30);
                rowsLow.set(store.updateDistilledThroughGuarded(convId, 50L));
            } catch (Throwable t) {
                error.set(t);
            }
        });
        Future<?> fHigh = pool.submit(() -> {
            try {
                ready.countDown();
                await(go, "go");
                rowsHigh.set(store.updateDistilledThroughGuarded(convId, 150L));
            } catch (Throwable t) {
                error.set(t);
            }
        });

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        fLow.get(10, TimeUnit.SECONDS);
        fHigh.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(error.get()).isNull();
        assertThat(rowsHigh.get()).isEqualTo(1);
        // 高水位先到后，低水位必须 0；若低水位偶发先到，高水位仍应把水位推到 150
        assertThat(store.selectById(convId).getDistilledThroughMessageId()).isEqualTo(150L);
        assertThat(rowsLow.get() + rowsHigh.get()).isLessThanOrEqualTo(2);
        assertThat(rowsLow.get()).isBetween(0, 1);
    }

    @Test
    @DisplayName("watermark 不得回退：旧 through → 0 行")
    void staleThrough_rejected() {
        InMemoryStore store = new InMemoryStore();
        CompanionConversation conv = conversation(9L, 100L);
        store.insert(conv);

        assertThat(store.updateDistilledThroughGuarded(9L, 50L)).isZero();
        assertThat(store.selectById(9L).getDistilledThroughMessageId()).isEqualTo(100L);
        assertThat(store.updateDistilledThroughGuarded(9L, 200L)).isEqualTo(1);
        assertThat(store.selectById(9L).getDistilledThroughMessageId()).isEqualTo(200L);
    }

    private static CompanionConversation conversation(long id, Long distilled) {
        CompanionConversation c = new CompanionConversation();
        c.setId(id);
        c.setStudentId(1L);
        c.setCourseId(2L);
        c.setDistilledThroughMessageId(distilled);
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }

    private static void await(CountDownLatch latch, String name) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timeout waiting for " + name);
        }
    }

    /** 与 updateDistilledThroughGuarded SQL 同语义的内存行存。 */
    static final class InMemoryStore {
        private final Map<Long, CompanionConversation> rows = new ConcurrentHashMap<>();

        synchronized void insert(CompanionConversation c) {
            rows.put(c.getId(), copyOf(c));
        }

        synchronized CompanionConversation selectById(Long id) {
            CompanionConversation row = rows.get(id);
            return row == null ? null : copyOf(row);
        }

        synchronized int updateDistilledThroughGuarded(Long id, Long throughId) {
            CompanionConversation row = rows.get(id);
            if (row == null) {
                return 0;
            }
            Long current = row.getDistilledThroughMessageId();
            if (current != null && current >= throughId) {
                return 0;
            }
            row.setDistilledThroughMessageId(throughId);
            row.setUpdatedAt(LocalDateTime.now());
            return 1;
        }

        private static CompanionConversation copyOf(CompanionConversation src) {
            CompanionConversation c = new CompanionConversation();
            c.setId(src.getId());
            c.setStudentId(src.getStudentId());
            c.setCourseId(src.getCourseId());
            c.setDistilledThroughMessageId(src.getDistilledThroughMessageId());
            c.setUpdatedAt(src.getUpdatedAt());
            return c;
        }
    }
}
