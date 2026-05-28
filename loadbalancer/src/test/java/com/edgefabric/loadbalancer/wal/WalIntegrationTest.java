package com.edgefabric.loadbalancer.wal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end WAL integration tests using a mock S3Client (no real AWS required).
 *
 * <p>Tests the full async pipeline:
 * {@code append()} → bounded queue → scheduled flush → S3 write (segment + checkpoint).
 */
@ExtendWith(MockitoExtension.class)
class WalIntegrationTest {

    @Mock
    private S3Client s3;

    private WalSegmentFlusher flusher;

    @BeforeEach
    void setUp() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("fresh start").build());
        flusher = new WalSegmentFlusher(s3, fastProps(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        flusher.destroy();
    }

    // ─────────────────── Basic end-to-end ────────────────────────────────────

    @Test
    void e2e_singlePut_writesSegmentAndCheckpointToS3() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        flusher.append(WalEntry.forPut("tenant:key1", "payload".getBytes(), 9_999_999L, "text/plain"));
        flusher.flushCycle();

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                // segment + checkpoint = 2 PutObject calls
                verify(s3, atLeast(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class))
        );
    }

    @Test
    void e2e_allEntriesFlushedInOneBatch() {
        AtomicInteger lineCount = new AtomicInteger(0);

        doAnswer(inv -> {
            PutObjectRequest req  = inv.getArgument(0);
            RequestBody      body = inv.getArgument(1);
            if (req.key().endsWith(".wal")) {
                String content = new String(
                        body.contentStreamProvider().newStream().readAllBytes(), StandardCharsets.UTF_8);
                lineCount.set((int) content.lines().count());
            }
            return PutObjectResponse.builder().build();
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        for (int i = 0; i < 5; i++) {
            flusher.append(WalEntry.forPut("k" + i, ("v" + i).getBytes(), 9_999_999L, "text/plain"));
        }
        flusher.flushCycle();

        Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(lineCount.get()).isEqualTo(5));
    }

    @Test
    void e2e_segmentKey_matchesExpectedPattern() {
        AtomicReference<String> capturedKey = new AtomicReference<>();

        doAnswer(inv -> {
            PutObjectRequest req = inv.getArgument(0);
            if (req.key().endsWith(".wal")) capturedKey.set(req.key());
            return PutObjectResponse.builder().build();
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        flusher.append(WalEntry.forPut("k", "v".getBytes(), 9_999_999L, "text/plain"));
        flusher.flushCycle();

        Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(capturedKey.get()).isNotNull());

        // Key format: wal/lb1/segments/<timestampMs>_<lsn10d>.wal
        assertThat(capturedKey.get()).matches("wal/lb1/segments/\\d+_\\d{10}\\.wal");
    }

    @Test
    void e2e_ndjsonEntry_containsRequiredFields() {
        AtomicReference<String> segmentContent = new AtomicReference<>();

        doAnswer(inv -> {
            PutObjectRequest req  = inv.getArgument(0);
            RequestBody      body = inv.getArgument(1);
            if (req.key().endsWith(".wal")) {
                segmentContent.set(new String(
                        body.contentStreamProvider().newStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            return PutObjectResponse.builder().build();
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        flusher.append(WalEntry.forPut("tenant:mykey", "hello".getBytes(), 9_999_999L, "application/json"));
        flusher.flushCycle();

        Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(segmentContent.get()).isNotNull());

        assertThat(segmentContent.get())
                .contains("\"lsn\"")
                .contains("\"key\"")
                .contains("\"expiresAt\"")
                .contains("\"operationType\":\"PUT\"")
                .contains("tenant:mykey");
    }

    @Test
    void e2e_checkpointKey_isPerLb() {
        AtomicReference<String> checkpointKey = new AtomicReference<>();

        doAnswer(inv -> {
            PutObjectRequest req = inv.getArgument(0);
            if (req.key().endsWith("checkpoint.json")) checkpointKey.set(req.key());
            return PutObjectResponse.builder().build();
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        flusher.append(WalEntry.forPut("k", "v".getBytes(), 9_999_999L, "text/plain"));
        flusher.flushCycle();

        Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(checkpointKey.get()).isNotNull());

        assertThat(checkpointKey.get()).isEqualTo("wal/lb1/checkpoint.json");
    }

    @Test
    void e2e_lsnIsMonotonicallyIncreasing_acrossMultipleFlushCycles() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        flusher.append(WalEntry.forPut("k1", "v1".getBytes(), 9_999_999L, "text/plain"));
        flusher.flushCycle();

        flusher.append(WalEntry.forPut("k2", "v2".getBytes(), 9_999_999L, "text/plain"));
        flusher.flushCycle();

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, atLeast(2)).putObject(captor.capture(), any(RequestBody.class));

        var segmentKeys = captor.getAllValues().stream()
                .map(PutObjectRequest::key)
                .filter(k -> k.endsWith(".wal"))
                .toList();
        assertThat(segmentKeys).hasSize(2);
        // LSN suffix must advance between cycles.
        assertThat(segmentKeys.get(0)).isNotEqualTo(segmentKeys.get(1));
    }

    @Test
    void e2e_noDeadLetters_underNormalOperation() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        for (int i = 0; i < 10; i++) {
            flusher.append(WalEntry.forPut("k" + i, "v".getBytes(), 9_999_999L, "text/plain"));
        }
        flusher.flushCycle();

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(s3, atLeast(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class))
        );

        assertThat(flusher.getDeadLetterCount()).isZero();
    }

    // ─────────────── Bounded drain / no-block guarantee ──────────────────────

    @Test
    void e2e_appendNeverBlocks_duringConcurrentFlush() throws InterruptedException {
        // S3 write is "slow" (50ms) — simulates a real network call.
        doAnswer(inv -> {
            Thread.sleep(50);
            return PutObjectResponse.builder().build();
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        // Start a flush in a background thread.
        flusher.append(WalEntry.forPut("first", "v".getBytes(), 9_999_999L, "text/plain"));
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<?> flushFuture = exec.submit(() -> flusher.flushCycle());

        // While the flush is in progress, appending should complete immediately
        // (queue has bufferHeadroom=10 free slots).
        long start = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            flusher.append(WalEntry.forPut("concurrent" + i, "v".getBytes(), 9_999_999L, "text/plain"));
        }
        long elapsed = System.currentTimeMillis() - start;

        // Append must be non-blocking — well under the 50ms S3 mock latency.
        assertThat(elapsed).isLessThan(40);

        try {
            flushFuture.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
        exec.shutdown();
        assertThat(flusher.getDeadLetterCount()).isZero();
    }

    // ───────────────── Timestamp ordering in segments ────────────────────────

    @Test
    void e2e_segmentEntriesAreOrderedByTimestamp() throws Exception {
        AtomicReference<String> segmentContent = new AtomicReference<>();

        doAnswer(inv -> {
            PutObjectRequest req  = inv.getArgument(0);
            RequestBody      body = inv.getArgument(1);
            if (req.key().endsWith(".wal")) {
                segmentContent.set(new String(
                        body.contentStreamProvider().newStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            return PutObjectResponse.builder().build();
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        // Append in REVERSE timestamp order (simulates clock skew or thread scheduling).
        flusher.append(new WalEntry("z_last",  null, 9999, "text/plain", 0L, OperationType.PUT, 3000L, java.util.Set.of(), java.util.Set.of()));
        flusher.append(new WalEntry("a_first", null, 9999, "text/plain", 0L, OperationType.PUT, 1000L, java.util.Set.of(), java.util.Set.of()));
        flusher.append(new WalEntry("m_mid",   null, 9999, "text/plain", 0L, OperationType.PUT, 2000L, java.util.Set.of(), java.util.Set.of()));
        flusher.flushCycle();

        Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(segmentContent.get()).isNotNull());

        String content = segmentContent.get();
        int posFirst = content.indexOf("\"key\":\"a_first\"");
        int posMid   = content.indexOf("\"key\":\"m_mid\"");
        int posLast  = content.indexOf("\"key\":\"z_last\"");

        assertThat(posFirst).isLessThan(posMid);
        assertThat(posMid).isLessThan(posLast);
    }

    // ─────────────── Multithreaded append correctness ────────────────────────

    @Test
    void e2e_concurrentAppends_noLsnCollisions() throws Exception {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        // flushBatchSize=20, capacity=30 in fastProps().
        // Fire 20 threads each appending 1 entry — well within capacity.
        int threadCount = 20;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await();   // synchronise all threads to maximise contention
                flusher.append(WalEntry.forPut("k" + idx, "v".getBytes(), 9_999_999L, "text/plain"));
                return null;
            }));
        }
        for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        // Flush all entries.
        flusher.flushCycle();

        // Capture segment content and check no duplicate LSNs.
        ArgumentCaptor<PutObjectRequest> reqCap  = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody>      bodyCap = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3, org.mockito.Mockito.timeout(5000).atLeastOnce()).putObject(reqCap.capture(), bodyCap.capture());

        List<String> lsns = new ArrayList<>();
        List<PutObjectRequest> reqs   = reqCap.getAllValues();
        List<RequestBody>      bodies = bodyCap.getAllValues();
        for (int i = 0; i < reqs.size(); i++) {
            if (reqs.get(i).key().endsWith(".wal")) {
                String content = new String(
                        bodies.get(i).contentStreamProvider().newStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                content.lines()
                        .map(line -> {
                            int start = line.indexOf("\"lsn\":") + 6;
                            int end   = line.indexOf(',', start);
                            return line.substring(start, end).trim();
                        })
                        .forEach(lsns::add);
            }
        }

        long uniqueLsns = lsns.stream().distinct().count();
        assertThat(uniqueLsns).isEqualTo(lsns.size()); // no duplicates
        assertThat(lsns.size()).isEqualTo(threadCount);
        assertThat(flusher.getDeadLetterCount()).isZero();
    }

    // ─── NEW test for EPMICMPHE-242: AC9 ────────────────────────────────────

    /**
     * AC9: When S3 listObjectsV2 returns an empty list (e.g. due to S3 lifecycle deletion),
     * replay() must complete without throwing and the handler must never be invoked.
     */
    @Test
    void replay_handlesEmptySegmentListGracefully() {
        // Arrange — listObjectsV2 returns empty non-truncated response
        ListObjectsV2Response emptyResponse = ListObjectsV2Response.builder()
                .contents(java.util.List.of())
                .isTruncated(false)
                .build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(emptyResponse);

        java.util.List<WalEntry> received = new java.util.ArrayList<>();

        // Act — must not throw
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> flusher.replay(received::add));

        // Assert — handler never called
        assertThat(received).isEmpty();
    }

    // ─────────────────────────── helpers ─────────────────────────────────────

    private WalProperties fastProps() {
        WalProperties p = new WalProperties();
        p.setEnabled(true);
        p.setStorage("s3");
        p.setS3Bucket("ef-hermes-wal");
        p.setLbId("lb1");
        p.setFlushIntervalMs(999999L);
        p.setSegmentSizeBytes(4 * 1024 * 1024);
        p.setFlushBatchSize(20);       // bounded batch
        p.setBufferHeadroom(10);       // capacity = 30
        p.setMaxFlushRetries(2);
        p.setRetryBackoffMs(0);
        return p;
    }
}
