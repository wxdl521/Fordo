package com.wenjin.support;

import com.wenjin.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiJobPayloadStoreTest {

    @TempDir Path tempDir;

    @Test
    void concurrentSameContentStore_neverPublishesPartialTarget() throws Exception {
        int writers = 12;
        byte[] content = new byte[8 * 1024 * 1024];
        Arrays.fill(content, (byte) 0x5a);
        AiJobPayloadStore store = new AiJobPayloadStore(tempDir.toString());
        CyclicBarrier start = new CyclicBarrier(writers);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<byte[]>> immediateLoads = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                immediateLoads.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    @SuppressWarnings("unchecked")
                    String hash = (String) store.storeBytes(
                            content, "same.bin", "application/octet-stream").get("fileHash");
                    return store.load(hash);
                }));
            }

            for (Future<byte[]> load : immediateLoads) {
                byte[] loaded = load.get(10, TimeUnit.SECONDS);
                assertThat(loaded).hasSize(content.length);
                assertThat(Arrays.equals(loaded, content)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void existingPartialTarget_isAtomicallyRepairedWithoutTempFiles() throws Exception {
        byte[] content = new byte[64 * 1024];
        Arrays.fill(content, (byte) 0x2d);
        String hash = AiJobPayloadStore.sha256Hex(content);
        Files.write(tempDir.resolve(hash), Arrays.copyOf(content, content.length / 2));
        AiJobPayloadStore store = new AiJobPayloadStore(tempDir.toString());

        store.storeBytes(content, "repair.bin", "application/octet-stream");

        byte[] repaired = store.load(hash);
        assertThat(repaired).hasSize(content.length);
        assertThat(Arrays.equals(repaired, content)).isTrue();
        try (var entries = Files.list(tempDir)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .containsExactly(hash);
        }
    }

    @Test
    void existingSameLengthTargetWithWrongDigest_isAtomicallyRepaired() throws Exception {
        byte[] content = new byte[64 * 1024];
        Arrays.fill(content, (byte) 0x3e);
        String hash = AiJobPayloadStore.sha256Hex(content);
        byte[] corrupt = content.clone();
        corrupt[corrupt.length / 2] ^= 0x7f;
        Files.write(tempDir.resolve(hash), corrupt);
        AiJobPayloadStore store = new AiJobPayloadStore(tempDir.toString());

        store.storeBytes(content, "repair.bin", "application/octet-stream");

        assertThat(store.load(hash)).containsExactly(content);
    }

    @Test
    void atomicMoveUnsupported_neverFallsBackToNonAtomicOverwrite() throws Exception {
        byte[] content = new byte[4096];
        Arrays.fill(content, (byte) 0x5c);
        String hash = AiJobPayloadStore.sha256Hex(content);
        byte[] corrupt = content.clone();
        corrupt[0] ^= 0x1f;
        Path target = tempDir.resolve(hash);
        Files.write(target, corrupt);
        AiJobPayloadStore store = new AiJobPayloadStore(tempDir.toString()) {
            @Override
            void moveAtomically(Path temp, Path destination) throws IOException {
                throw new AtomicMoveNotSupportedException(temp.toString(), destination.toString(), "test");
            }
        };

        assertThatThrownBy(() -> store.storeBytes(content, "payload.bin", "application/octet-stream"))
                .isInstanceOf(BusinessException.class);

        assertThat(Files.readAllBytes(target)).containsExactly(corrupt);
        try (var entries = Files.list(tempDir)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .containsExactly(hash);
        }
    }

    @Test
    void atomicMoveUnsupported_reusesTargetPublishedCompletelyByConcurrentWriter() {
        byte[] content = new byte[4096];
        Arrays.fill(content, (byte) 0x6d);
        String hash = AiJobPayloadStore.sha256Hex(content);
        AiJobPayloadStore store = new AiJobPayloadStore(tempDir.toString()) {
            @Override
            void moveAtomically(Path temp, Path destination) throws IOException {
                Files.write(destination, content);
                throw new AtomicMoveNotSupportedException(temp.toString(), destination.toString(), "test");
            }
        };

        store.storeBytes(content, "payload.bin", "application/octet-stream");

        assertThat(store.load(hash)).containsExactly(content);
    }

    @Test
    void deleteAndConfirmAbsentDistinguishesSafeDeletionFromInvalidReference() {
        byte[] content = new byte[]{1, 2, 3, 4};
        AiJobPayloadStore store = new AiJobPayloadStore(tempDir.toString());
        String hash = (String) store.storeBytes(
                content, "payload.bin", "application/octet-stream").get("fileHash");

        assertThat(store.deleteAndConfirmAbsent(hash)).isTrue();
        assertThat(store.deleteAndConfirmAbsent(hash)).isTrue();
        assertThat(store.deleteAndConfirmAbsent("not-a-hash")).isFalse();
    }
}
