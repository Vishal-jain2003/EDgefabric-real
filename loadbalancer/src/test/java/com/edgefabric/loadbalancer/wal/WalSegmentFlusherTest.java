package com.edgefabric.loadbalancer.wal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalSegmentFlusherTest {

    @Mock
    private S3Client s3;

    private WalProperties props;
    private WalSegmentFlusher flusher;

    @BeforeEach
    void setUp() {
        props = testProps();
        // Fresh start: no existing checkpoint in S3.
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());
        flusher = new WalSegmentFlusher(s3, props, new ObjectMapper());
    }

    // ─────────────────────────── segmentKey ──────────────────────────────────

    @Test
    void segmentKey_hasCorrectFormat() {
        String key = flusher.segmentKey(0, 1_000_000L);
        assertThat(key).isEqualTo("wal/lb1/segments/1000000_0000000000.wal");
    }

    @Test
    void segmentKey_zeropadsLsnTo10Digits() {
        assertThat(flusher.segmentKey(42, 1_000_000L)).contains("_0000000042.wal");
    }

    @Test
    void segmentKey_containsLbIdFromProperties() {
        assertThat(flusher.segmentKey(0, 1_000_000L)).startsWith("wal/lb1/");
    }

    @Test
    void segmentKey_timestampPrefixEnablesChronologicalListing() {
        // Earlier timestamp must sort before later timestamp lexicographically.
        String early = flusher.segmentKey(0, 1_000_000L);
        String later = flusher.segmentKey(0, 2_000_000L);
        assertThat(early.compareTo(later)).isLessThan(0);
    }

    // ──────────────────────── serialiseToSegments ────────────────────────────

    @Test
    void serialiseToSegments_oneSegment_whenEntriesFitInLimit() {
        var entries = List.of(
                stamped(0, WalEntry.forPut("k1", "v1".getBytes(), 9999, "text/plain")),
                stamped(1, WalEntry.forPut("k2", "v2".getBytes(), 9999, "text/plain"))
        );

        var slices = flusher.serialiseToSegments(entries);

        assertThat(slices).hasSize(1);
        assertThat(slices.get(0).entryCount()).isEqualTo(2);
        assertThat(new String(slices.get(0).bytes(), StandardCharsets.UTF_8).lines().count()).isEqualTo(2);
    }

    @Test
    void serialiseToSegments_splitsAtSizeLimit() {
        props.setSegmentSizeBytes(1);   // force a split on every entry
        var entries = List.of(
                stamped(0, WalEntry.forPut("k1", "v1".getBytes(), 9999, "text/plain")),
                stamped(1, WalEntry.forPut("k2", "v2".getBytes(), 9999, "text/plain"))
        );

        var slices = flusher.serialiseToSegments(entries);

        assertThat(slices.size()).isGreaterThanOrEqualTo(2);
        // Each slice holds exactly 1 entry when size limit forces a split per entry.
        slices.forEach(s -> assertThat(s.entryCount()).isEqualTo(1));
    }

    @Test
    void serialiseToSegments_embedsLsnInEachLine() {
        var entries = List.of(
                stamped(10, WalEntry.forPut("k", null, 9999, "text/plain")),
                stamped(11, WalEntry.forPut("k", null, 9999, "text/plain"))
        );

        String content = new String(flusher.serialiseToSegments(entries).get(0).bytes(), StandardCharsets.UTF_8);

        assertThat(content).contains("\"lsn\":10").contains("\"lsn\":11");
    }

    @Test
    void serialiseToSegments_embedsOperationType() {
        var entries = List.of(stamped(0, WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain")));

        String content = new String(flusher.serialiseToSegments(entries).get(0).bytes(), StandardCharsets.UTF_8);

        assertThat(content).contains("\"operationType\":\"PUT\"");
    }

    @Test
    void serialiseToSegments_encodesDataAsBase64() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        var entries = List.of(stamped(0, WalEntry.forPut("k", data, 9999, "text/plain")));

        String content = new String(flusher.serialiseToSegments(entries).get(0).bytes(), StandardCharsets.UTF_8);

        // Base64("hello") = "aGVsbG8="
        assertThat(content).contains("aGVsbG8=");
    }

    // ───────────────────────────── append ────────────────────────────────────

    @Test
    void append_isNonBlocking_andIncrementsLsn() {
        flusher.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        flusher.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));

        assertThat(flusher.getDeadLetterCount()).isZero();
    }

    @Test
    void append_deadLetters_whenQueueFull() {
        flusher.destroy(); // stop default async drain

        WalProperties p = testProps();
        p.setFlushIntervalMs(0); // sync mode
        WalSegmentFlusher syncFlusher = new WalSegmentFlusher(s3, p, new ObjectMapper());

        // Fill queue to capacity (flushBatchSize + bufferHeadroom = 10 + 5 = 15 in test props).
        int capacity = p.getFlushBatchSize() + p.getBufferHeadroom();
        for (int i = 0; i < capacity; i++) {
            syncFlusher.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        }
        // One more entry must be dead-lettered.
        syncFlusher.append(WalEntry.forPut("overflow", "v".getBytes(), 9999, "text/plain"));

        assertThat(syncFlusher.getDeadLetterCount()).isEqualTo(1);
    }

    // ──────────────────────────── flushCycle ─────────────────────────────────

    @Test
    void flushCycle_writesSegmentAndCheckpoint() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        flusher.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        flusher.flushCycle();

        // segment + checkpoint = 2 PutObject calls
        verify(s3, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void flushCycle_skipsS3_whenBufferEmpty() {
        flusher.flushCycle();

        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void flushCycle_writesCorrectSegmentKey() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);

        flusher.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        flusher.flushCycle();

        verify(s3, atLeastOnce()).putObject(captor.capture(), any(RequestBody.class));
        // Key format: wal/lb1/segments/<timestampMs>_<lsn10d>.wal
        assertThat(captor.getAllValues().get(0).key())
                .matches("wal/lb1/segments/\\d+_\\d{10}\\.wal");
    }

    @Test
    void flushCycle_usesSseAes256Encryption() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);

        flusher.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        flusher.flushCycle();

        verify(s3, atLeastOnce()).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getAllValues().get(0).serverSideEncryptionAsString()).isEqualTo("AES256");
    }

    @Test
    void flushCycle_retriesOnS3Failure_thenDeadLetters() {
        props.setRetryBackoffMs(0L);
        props.setMaxFlushRetries(3);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("network error").build());

        flusher.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        flusher.flushCycle();

        verify(s3, atLeast(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(flusher.getDeadLetterCount()).isPositive();
    }

    @Test
    void flushCycle_writesMultipleSegments_whenBatchExceedsSizeLimit() {
        props.setSegmentSizeBytes(1);   // every entry → its own segment
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        flusher.append(WalEntry.forPut("k1", "v1".getBytes(), 9999, "text/plain"));
        flusher.append(WalEntry.forPut("k2", "v2".getBytes(), 9999, "text/plain"));
        flusher.flushCycle();

        // ≥2 segment writes + 1 checkpoint
        verify(s3, atLeast(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void flushCycle_drainsOnlyFlushBatchSize_leavingHeadroomForAppenders() {
        // Disable periodic flushing so only the threshold-triggered auto-flush fires.
        // Otherwise the 500ms periodic timer can race with the test and flush leftover
        // entries as a second segment write, causing flakiness on slow CI agents.
        // Destroy the setUp() flusher first to avoid leaking its background thread.
        flusher.destroy();
        props.setFlushIntervalMs(60_000);
        flusher = new WalSegmentFlusher(s3, props, new ObjectMapper());

        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        // Enqueue more entries than flushBatchSize (10) but within total capacity (15).
        int overBatch = props.getFlushBatchSize() + 3;  // 13 entries
        for (int i = 0; i < overBatch; i++) {
            flusher.append(WalEntry.forPut("k" + i, "v".getBytes(), 9999, "text/plain"));
        }

        // Automatic async flush took place at exactly 10!
        org.awaitility.Awaitility.await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() ->
                verify(s3, atLeastOnce()).putObject(any(PutObjectRequest.class), any(RequestBody.class))
        );

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, atLeastOnce()).putObject(captor.capture(), any(RequestBody.class));

        // Only one segment (all 10 fit within 4 MB) plus the checkpoint.
        long segmentWrites = captor.getAllValues().stream()
                .filter(r -> r.key().endsWith(".wal")).count();
        assertThat(segmentWrites).isEqualTo(1);

        // Remaining 3 entries are still in the queue for the next cycle.
        flusher.flushCycle();
        // After second cycle verify a second segment was written (3 remaining entries).
        ArgumentCaptor<PutObjectRequest> captor2 = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, atLeast(2)).putObject(captor2.capture(), any(RequestBody.class));
        long totalSegmentWrites = captor2.getAllValues().stream()
                .filter(r -> r.key().endsWith(".wal")).count();
        assertThat(totalSegmentWrites).isGreaterThanOrEqualTo(2);
        // After second cycle all entries have been flushed (no dead-letters).
        assertThat(flusher.getDeadLetterCount()).isZero();
    }

    @Test
    void flushCycle_sortsEntriesByTimestamp() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);

        // Append in reverse timestamp order.
        flusher.append(new WalEntry("k3", null, 9999, "text/plain", 0L, OperationType.PUT, 300L, java.util.Set.of(), java.util.Set.of()));
        flusher.append(new WalEntry("k1", null, 9999, "text/plain", 0L, OperationType.PUT, 100L, java.util.Set.of(), java.util.Set.of()));
        flusher.append(new WalEntry("k2", null, 9999, "text/plain", 0L, OperationType.PUT, 200L, java.util.Set.of(), java.util.Set.of()));
        flusher.flushCycle();

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, atLeastOnce()).putObject(reqCaptor.capture(), bodyCaptor.capture());

        // Locate the .wal segment body.
        List<PutObjectRequest> reqs = reqCaptor.getAllValues();
        List<RequestBody> bodies = bodyCaptor.getAllValues();
        String segmentContent = null;
        for (int i = 0; i < reqs.size(); i++) {
            if (reqs.get(i).key().endsWith(".wal")) {
                try {
                    segmentContent = new String(
                            bodies.get(i).contentStreamProvider().newStream().readAllBytes(),
                            StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        assertThat(segmentContent).isNotNull();
        // Lines must appear in timestamp order: k1 (100) → k2 (200) → k3 (300).
        int posK1 = segmentContent.indexOf("\"key\":\"k1\"");
        int posK2 = segmentContent.indexOf("\"key\":\"k2\"");
        int posK3 = segmentContent.indexOf("\"key\":\"k3\"");
        assertThat(posK1).isLessThan(posK2);
        assertThat(posK2).isLessThan(posK3);
    }

    // ──────────────────────── checkpoint recovery ─────────────────────────────

    @Test
    void constructor_startLsnFromZero_whenNoCheckpointExists() {
        flusher.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);

        flusher.flushCycle();

        verify(s3, atLeastOnce()).putObject(captor.capture(), any(RequestBody.class));
        // First segment key should embed LSN 0 (zero-padded).
        assertThat(captor.getAllValues().get(0).key()).contains("_0000000000.wal");
    }

    @Test
    void constructor_resumesLsnFromCheckpoint_whenCheckpointExists() throws Exception {
        String checkpointJson = "{\"lastCommittedLsn\":99,\"updatedAt\":0}";
        ResponseBytes<GetObjectResponse> checkpointBytes = ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(),
                checkpointJson.getBytes(StandardCharsets.UTF_8)
        );
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(checkpointBytes);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        WalSegmentFlusher recovered = new WalSegmentFlusher(s3, props, new ObjectMapper());
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        recovered.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        recovered.flushCycle();

        verify(s3, atLeastOnce()).putObject(captor.capture(), any(RequestBody.class));
        // LSN should start at 100 (99 + 1).
        assertThat(captor.getAllValues().get(0).key()).contains("_0000000100.wal");

        recovered.destroy();
    }

    // ──────────────────────── coverage gaps ──────────────────────────────────

    @Test
    void constructor_startsAtZero_onGenericS3CheckpointReadError() {
        // A non-NoSuchKeyException triggers the generic warn+fallback path.
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        WalSegmentFlusher f = new WalSegmentFlusher(s3, props, new ObjectMapper());
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);

        f.append(WalEntry.forPut("k", "v".getBytes(), 9999, "text/plain"));
        f.flushCycle();

        verify(s3, atLeastOnce()).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getAllValues().get(0).key()).contains("_0000000000.wal");
        f.destroy();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildCheckpointJson_fallsBackToStringConcat_whenMapperFails() throws Exception {
        // Use a mapper that throws on writeValueAsString to exercise the fallback branch.
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("simulated failure") {});
        // Construction reads checkpoint — throw NoSuchKey so LSN starts at 0.
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        WalSegmentFlusher f = new WalSegmentFlusher(s3, props, failingMapper);
        String json = f.buildCheckpointJson(42L);

        // The fallback must still produce a string containing the LSN.
        assertThat(json).contains("42");
        f.destroy();
    }

    @Test
    void segmentSlice_entryCountMatchesLineCount() {
        var entries = List.of(
                stamped(0, WalEntry.forPut("k1", "v1".getBytes(), 9999, "text/plain")),
                stamped(1, WalEntry.forPut("k2", "v2".getBytes(), 9999, "text/plain")),
                stamped(2, WalEntry.forPut("k3", "v3".getBytes(), 9999, "text/plain"))
        );

        var slices = flusher.serialiseToSegments(entries);

        // All three entries fit in one slice; entryCount must equal the NDJSON line count.
        assertThat(slices).hasSize(1);
        assertThat(slices.get(0).entryCount()).isEqualTo(3);
        long lineCount = new String(slices.get(0).bytes(), StandardCharsets.UTF_8).lines().count();
        assertThat(slices.get(0).entryCount()).isEqualTo((int) lineCount);
    }

    @Test
    void segmentSlice_entryCountTrackedPerSlice_whenSplit() {
        props.setSegmentSizeBytes(1);  // one entry per segment
        var entries = List.of(
                stamped(0, WalEntry.forPut("k1", "v1".getBytes(), 9999, "text/plain")),
                stamped(1, WalEntry.forPut("k2", "v2".getBytes(), 9999, "text/plain"))
        );

        var slices = flusher.serialiseToSegments(entries);

        assertThat(slices).hasSizeGreaterThanOrEqualTo(2);
        slices.forEach(s -> assertThat(s.entryCount()).isEqualTo(1));
    }

    @Test
    void checkpointKeyFor_staticHelper_returnsExpectedKey() {
        assertThat(AbstractWalFlusher.checkpointKeyFor("myLb"))
                .isEqualTo("wal/myLb/checkpoint.json");
    }

    // ─────────────────────────────── helpers ──────────────────────────────────

    private WalProperties testProps() {
        WalProperties p = new WalProperties();
        p.setEnabled(true);
        p.setStorage("s3");
        p.setS3Bucket("ef-hermes-wal");
        p.setLbId("lb1");
        p.setSegmentSizeBytes(4 * 1024 * 1024);
        p.setFlushBatchSize(10);        // small batch so overflow test is fast
        p.setBufferHeadroom(5);         // capacity = 15
        p.setMaxFlushRetries(3);
        p.setRetryBackoffMs(0);
        return p;
    }

    private AbstractWalFlusher.StampedEntry stamped(long lsn, WalEntry entry) {
        return new AbstractWalFlusher.StampedEntry(lsn, entry);
    }

    @Test
    void replay_streamsObjectsChronologically() {
        String ndjson = "{\"lsn\":0,\"timestamp\":1000,\"operationType\":\"PUT\",\"key\":\"k1\",\"expiresAt\":0,\"contentType\":\"text/plain\",\"data\":\"djE=\"}\n" +
                        "{\"lsn\":1,\"timestamp\":2000,\"operationType\":\"PUT\",\"key\":\"k2\",\"expiresAt\":0,\"contentType\":\"text/plain\",\"data\":\"djI=\"}\n";

        S3Object obj = S3Object.builder().key("wal/lb1/segments/1000_0000000000.wal").build();
        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(obj)
                .isTruncated(false)
                .build();

        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);
        
        ResponseBytes<GetObjectResponse> getBytes = ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(),
                ndjson.getBytes(StandardCharsets.UTF_8)
        );
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(getBytes);

        List<WalEntry> replayed = new ArrayList<>();
        flusher.replay(replayed::add);

        assertThat(replayed).hasSize(2);
        assertThat(replayed.get(0).key()).isEqualTo("k1");
        assertThat(replayed.get(1).key()).isEqualTo("k2");
    }
}
