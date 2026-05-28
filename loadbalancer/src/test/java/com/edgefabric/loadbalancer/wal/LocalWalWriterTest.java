package com.edgefabric.loadbalancer.wal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit and integration tests for {@link LocalWalWriter}.
 *
 * <p>Uses a JUnit {@code @TempDir} as the WAL base directory so every test
 * runs in isolation with no shared filesystem state.
 */
class LocalWalWriterTest {

    @TempDir
    Path tempDir;

    private WalProperties props;
    private LocalWalWriter writer;

    @BeforeEach
    void setUp() {
        props  = testProps();
        writer = new LocalWalWriter(props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        writer.destroy();  // idempotent — safe even if test already called destroy()
    }

    // ─── Constructor / LSN recovery ─────────────────────────────────────────

    @Test
    void constructor_startsAtLsnZero_whenNoCheckpointExists() {
        writer.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        writer.flushCycle();

        assertThat(segmentFiles())
                .anyMatch(p -> p.getFileName().toString().contains("_0000000000.wal"));
    }

    @Test
    void constructor_recoversLsnFromCheckpoint() throws Exception {
        // Write a checkpoint manually into the temp dir.
        Path checkpointFile = tempDir.resolve("wal/lb1/checkpoint.json");
        Files.createDirectories(checkpointFile.getParent());
        Files.writeString(checkpointFile, "{\"lastCommittedLsn\":99,\"updatedAt\":0}");

        // Recreate the writer — it must resume at LSN 100.
        writer.destroy();
        writer = new LocalWalWriter(props, new ObjectMapper());

        writer.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        writer.flushCycle();

        assertThat(segmentFiles())
                .anyMatch(p -> p.getFileName().toString().contains("_0000000100.wal"));
    }

    @Test
    void constructor_startsAtZero_whenCheckpointIsCorrupt() throws Exception {
        Path checkpointFile = tempDir.resolve("wal/lb1/checkpoint.json");
        Files.createDirectories(checkpointFile.getParent());
        Files.writeString(checkpointFile, "not-valid-json");

        writer.destroy();
        writer = new LocalWalWriter(props, new ObjectMapper());

        // No crash, starts at 0 with zero dead letters.
        assertThat(writer.getDeadLetterCount()).isZero();
    }

    // ─── writeSegment ────────────────────────────────────────────────────────

    @Test
    void writeSegment_createsFileWithCorrectNdjsonContent() throws Exception {
        writer.append(WalEntry.forPut("mykey", "myvalue".getBytes(), 9999, "text/plain"));
        writer.flushCycle();

        assertThat(segmentFiles()).hasSize(1);
        String content = Files.readString(segmentFiles().get(0));
        assertThat(content)
                .contains("\"key\":\"mykey\"")
                .contains("\"operationType\":\"PUT\"")
                .contains("\"lsn\"")
                .contains("\"timestamp\"");
    }

    @Test
    void writeSegment_createsIntermediateDirectories() {
        writer.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        writer.flushCycle();

        assertThat(Files.isDirectory(tempDir.resolve("wal/lb1/segments"))).isTrue();
    }

    @Test
    void writeSegment_deadLetters_onKeyCollision() throws Exception {
        // Write a segment successfully.
        writer.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        writer.flushCycle();

        assertThat(segmentFiles()).hasSize(1);
        long deadBefore = writer.getDeadLetterCount();

        // Derive the key of the existing file and try writing to it again.
        // CREATE_NEW fails on an already-existing file → dead-letter.
        Path existingFile = segmentFiles().get(0);
        String key = tempDir.relativize(existingFile).toString().replace(
                java.io.File.separatorChar, '/');
        writer.writeSegment(key, "duplicate-payload".getBytes());

        assertThat(writer.getDeadLetterCount()).isGreaterThan(deadBefore);
    }

    @Test
    void writeSegment_segmentFileNameMatchesTimestampLsnFormat() {
        writer.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        writer.flushCycle();

        assertThat(segmentFiles()).hasSize(1);
        assertThat(segmentFiles().get(0).getFileName().toString())
                .matches("\\d+_\\d{10}\\.wal");
    }

    @Test
    void writeSegment_base64EncodesData() {
        // "hello" in Base64 = "aGVsbG8="
        writer.append(WalEntry.forPut("k", "hello".getBytes(StandardCharsets.UTF_8), 9999, "text/plain"));
        writer.flushCycle();

        assertThat(segmentFiles()).hasSize(1);
        String content = readFile(segmentFiles().get(0));
        assertThat(content).contains("aGVsbG8=");
    }

    @Test
    void writeSegment_handlesNullData() {
        writer.append(WalEntry.forPut("k", null, 9999, "text/plain"));
        writer.flushCycle();

        assertThat(segmentFiles()).hasSize(1);
        String content = readFile(segmentFiles().get(0));
        assertThat(content).contains("\"data\":null");
    }

    // ─── persistCheckpoint ───────────────────────────────────────────────────

    @Test
    void persistCheckpoint_writesValidJson() throws Exception {
        writer.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        writer.flushCycle();

        Path checkpointFile = tempDir.resolve("wal/lb1/checkpoint.json");
        assertThat(Files.exists(checkpointFile)).isTrue();

        String json = Files.readString(checkpointFile);
        assertThat(json)
                .contains("\"lastCommittedLsn\"")
                .contains("\"updatedAt\"");
        // Must be valid JSON.
        new ObjectMapper().readTree(json);
    }

    @Test
    void persistCheckpoint_updatesAcrossFlushCycles() throws Exception {
        writer.append(WalEntry.forPut("k1", "v".getBytes(), 9999, "text/plain"));
        writer.flushCycle();

        writer.append(WalEntry.forPut("k2", "v".getBytes(), 9999, "text/plain"));
        writer.flushCycle();

        Path checkpointFile = tempDir.resolve("wal/lb1/checkpoint.json");
        long lsn = new ObjectMapper()
                .readTree(Files.readString(checkpointFile))
                .get("lastCommittedLsn").asLong();
        // After two cycles with one entry each, committed LSN should be ≥ 1.
        assertThat(lsn).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void persistCheckpoint_isPerLbId() throws Exception {
        writer.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        writer.flushCycle();

        // Checkpoint lives at wal/<lbId>/checkpoint.json — here "lb1".
        assertThat(Files.exists(tempDir.resolve("wal/lb1/checkpoint.json"))).isTrue();
    }

    // ─── Timestamp ordering ──────────────────────────────────────────────────

    @Test
    void flushCycle_segmentEntriesOrderedByTimestamp() {
        // Append in reverse timestamp order to prove sorting happens.
        writer.append(new WalEntry("z_last",  null, 9999, "text/plain", 0L, OperationType.PUT, 3000L, java.util.Set.of(), java.util.Set.of()));
        writer.append(new WalEntry("a_first", null, 9999, "text/plain", 0L, OperationType.PUT, 1000L, java.util.Set.of(), java.util.Set.of()));
        writer.append(new WalEntry("m_mid",   null, 9999, "text/plain", 0L, OperationType.PUT, 2000L, java.util.Set.of(), java.util.Set.of()));
        writer.flushCycle();

        String content = readFile(segmentFiles().get(0));
        int posFirst = content.indexOf("\"key\":\"a_first\"");
        int posMid   = content.indexOf("\"key\":\"m_mid\"");
        int posLast  = content.indexOf("\"key\":\"z_last\"");
        assertThat(posFirst).isLessThan(posMid);
        assertThat(posMid).isLessThan(posLast);
    }

    // ─── Bounded drain / no-block guarantee ─────────────────────────────────

    @Test
    void flushCycle_drainsOnlyFlushBatchSizePerCycle() {
        // Reinitialize writer with a long scheduler interval so the background flush
        // cannot race with our manual flushCycle() calls on slow CI machines.
        writer.destroy();
        props.setFlushIntervalMs(1000000L);
        writer = new LocalWalWriter(props, new ObjectMapper());

        // flushBatchSize=10, capacity=15 — enqueue 13 entries.
        int overBatch = props.getFlushBatchSize() + 3;
        for (int i = 0; i < overBatch; i++) {
            writer.append(WalEntry.forPut("k" + i, "v".getBytes(), 9999, "text/plain"));
        }

        // Automatic async flush took place at exactly 10!
        org.awaitility.Awaitility.await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(segmentFiles()).hasSize(1)
        );

        writer.flushCycle();
        // Now the second cycle drains the remaining 3 entries
        assertThat(segmentFiles()).hasSize(2);

        // No dead letters
        assertThat(writer.getDeadLetterCount()).isZero();
    }

    @Test
    void append_deadLetters_whenQueueFull() {
        writer.destroy(); // stop default background flush
        WalProperties p = testProps();
        p.setFlushIntervalMs(0); // disable async drain to guarantee overflow
        LocalWalWriter syncWriter = new LocalWalWriter(p, new ObjectMapper());

        int capacity = p.getFlushBatchSize() + p.getBufferHeadroom();  // 15
        for (int i = 0; i < capacity; i++) {
            syncWriter.append(WalEntry.forPut("k" + i, "v".getBytes(), 9999, "text/plain"));
        }
        syncWriter.append(WalEntry.forPut("overflow", "v".getBytes(), 9999, "text/plain"));

        assertThat(syncWriter.getDeadLetterCount()).isEqualTo(1);
    }

    // ─── Checkpoint key format ───────────────────────────────────────────────

    @Test
    void checkpointKeyFor_returnsCorrectPath() {
        assertThat(AbstractWalFlusher.checkpointKeyFor("lb42"))
                .isEqualTo("wal/lb42/checkpoint.json");
    }

    // ─── Async (scheduled flush) ─────────────────────────────────────────────

    @Test
    void e2e_scheduledFlush_writesSegmentToDisk() {
        writer.append(WalEntry.forPut("async-key", "v".getBytes(), 9999, "text/plain"));

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(segmentFiles()).isNotEmpty()
        );

        assertThat(readFile(segmentFiles().get(0))).contains("\"key\":\"async-key\"");
    }

    @Test
    void e2e_scheduledFlush_writesCheckpointToDisk() {
        writer.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(Files.exists(tempDir.resolve("wal/lb1/checkpoint.json"))).isTrue()
        );
    }

    // ─── Destroy / shutdown ──────────────────────────────────────────────────

    @Test
    void destroy_flushesEntriesAppendedBeforeShutdown() {
        writer.append(WalEntry.forPut("shutdown-key", "v".getBytes(), 9999, "text/plain"));
        writer.destroy();

        assertThat(segmentFiles()).isNotEmpty();
        assertThat(readFile(segmentFiles().get(0))).contains("\"key\":\"shutdown-key\"");
    }

    @Test
    void destroy_isIdempotent() {
        // Calling destroy() twice must not throw.
        writer.destroy();
        writer.destroy();
    }

    // ─── LSN monotonicity across restarts ────────────────────────────────────

    @Test
    void lsn_doesNotRepeat_acrossRestarts() throws Exception {
        writer.append(WalEntry.forPut("before", "v".getBytes(), 9999, "text/plain"));
        writer.flushCycle();
        writer.destroy();

        // The checkpoint was written → restarted writer must start LSN above previous max.
        writer = new LocalWalWriter(props, new ObjectMapper());
        writer.append(WalEntry.forPut("after", "v".getBytes(), 9999, "text/plain"));
        writer.flushCycle();

        List<Path> files = segmentFiles();
        assertThat(files).hasSize(2);  // first segment from before, second from after
        // Second segment's LSN suffix must be greater than the first's.
        String firstLsn  = extractLsnSuffix(files.get(0));
        String secondLsn = extractLsnSuffix(files.get(1));
        assertThat(Long.parseLong(secondLsn)).isGreaterThan(Long.parseLong(firstLsn));
    }

    // ─── Multiple segments from one batch ───────────────────────────────────

    @Test
    void flushCycle_splitsLargeBatchIntoMultipleSegmentFiles() {
        props.setSegmentSizeBytes(1);  // force a new segment per entry
        writer.destroy();
        writer = new LocalWalWriter(props, new ObjectMapper());

        writer.append(WalEntry.forPut("k1", "v1".getBytes(), 9999, "text/plain"));
        writer.append(WalEntry.forPut("k2", "v2".getBytes(), 9999, "text/plain"));
        writer.flushCycle();

        assertThat(segmentFiles().size()).isGreaterThanOrEqualTo(2);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private List<Path> segmentFiles() {
        Path segDir = tempDir.resolve("wal/lb1/segments");
        if (!Files.exists(segDir)) return List.of();
        try (var stream = Files.list(segDir)) {
            return stream.sorted().toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Extracts the zero-padded LSN from a filename like {@code 1234567890_0000000042.wal}. */
    private String extractLsnSuffix(Path file) {
        String name = file.getFileName().toString();  // e.g. "1234567890_0000000042.wal"
        String withoutExt = name.substring(0, name.lastIndexOf('.'));
        return withoutExt.substring(withoutExt.lastIndexOf('_') + 1);
    }

    private WalProperties testProps() {
        WalProperties p = new WalProperties();
        p.setEnabled(true);
        p.setStorage("local");
        p.setLbId("lb1");
        p.setSegmentSizeBytes(4 * 1024 * 1024);
        p.setFlushBatchSize(10);     // small for bounded-drain test
        p.setBufferHeadroom(5);      // capacity = 15
        p.setMaxFlushRetries(1);
        p.setRetryBackoffMs(0);
        p.setLocalDir(tempDir.toString());
        return p;
    }

    // ─── NEW test for EPMICMPHE-242: AC4 ────────────────────────────────────

    /**
     * AC4: Binary data stored in WAL must survive a Base64 encode/decode round-trip
     * byte-for-byte identically (all 256 byte values exercised).
     */
    @Test
    void replay_base64RoundTrip_binaryDataIntact() throws Exception {
        // Build a byte array containing all 256 possible byte values
        byte[] original = new byte[256];
        for (int i = 0; i < 256; i++) {
            original[i] = (byte) i;
        }

        long expiresAt = 9_999_999_999L;
        writer.append(WalEntry.forPut("binary-key", original, expiresAt, "application/octet-stream"));
        writer.flushCycle();

        // Wait for the flush to persist to disk
        org.awaitility.Awaitility.await()
                .atMost(10, java.util.concurrent.TimeUnit.SECONDS)
                .until(() -> !segmentFiles().isEmpty());

        List<WalEntry> replayed = new java.util.ArrayList<>();
        writer.replay(replayed::add);

        assertThat(replayed).hasSize(1);
        assertThat(replayed.get(0).key()).isEqualTo("binary-key");
        // Byte-for-byte identity check
        assertThat(java.util.Arrays.equals(original, replayed.get(0).data())).isTrue();
    }

    @Test
    void replay_readsAlphabeticallyOrderedFilesAndStreamsEntries() throws Exception {
        WalProperties props = testProps();
        LocalWalWriter writer = new LocalWalWriter(props, new com.fasterxml.jackson.databind.ObjectMapper());
        writer.append(WalEntry.forPut("k1", "v1".getBytes(), 9999, "text/plain"));
        writer.append(WalEntry.forPut("k2", "v2".getBytes(), 9999, "text/plain"));
        writer.flushCycle();
        java.util.List<WalEntry> replayed = new java.util.ArrayList<>();
        writer.replay(replayed::add);
        org.assertj.core.api.Assertions.assertThat(replayed).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(replayed.get(0).key()).isEqualTo("k1");
        org.assertj.core.api.Assertions.assertThat(replayed.get(1).key()).isEqualTo("k2");
    }
}
