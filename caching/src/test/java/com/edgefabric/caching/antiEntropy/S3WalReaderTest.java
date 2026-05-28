package com.edgefabric.caching.antiEntropy;

import com.edgefabric.caching.config.WalReaderProperties;
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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link S3WalReader}.
 *
 * <p>Tests WAL segment filtering logic (LSN, expiry, nodeId, operationType)
 * and checkpoint read/write behaviour.</p>
 */
@ExtendWith(MockitoExtension.class)
class S3WalReaderTest {

    @Mock
    private S3Client s3;

    private S3WalReader walReader;
    private WalReaderProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String BUCKET = "ef-hermes-wal";
    private static final String LB_ID  = "lb1";
    private static final String NODE_ID = "node1";

    @BeforeEach
    void setUp() {
        props = new WalReaderProperties();
        props.setS3Bucket(BUCKET);
        props.setLbId(LB_ID);
        walReader = new S3WalReader(s3, props, objectMapper);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Returns a NoSuchKeyException so that loadCheckpoint returns 0
     * (no prior checkpoint on file).
     */
    private void stubNoCheckpoint() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenAnswer(inv -> {
                    GetObjectRequest req = inv.getArgument(0);
                    String key = req.key();
                    if (key.contains("node-checkpoints")) {
                        throw NoSuchKeyException.builder().message("not found").build();
                    }
                    throw new AssertionError("Unexpected getObjectAsBytes for key: " + key);
                });
    }

    /**
     * Stubs S3 to return a single checkpoint file with the given LSN,
     * then for subsequent getObjectAsBytes calls (segment reads) uses
     * the provided segment bytes.
     */
    private void stubCheckpointLsn(long lsn, byte[] segmentBytes) throws Exception {
        String checkpointJson = objectMapper.writeValueAsString(
                new java.util.HashMap<String, Object>() {{
                    put("lastRepairedLsn", lsn);
                    put("updatedAt", System.currentTimeMillis());
                }}
        );
        byte[] checkpointBytes = checkpointJson.getBytes(StandardCharsets.UTF_8);

        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenAnswer(inv -> {
                    GetObjectRequest req = inv.getArgument(0);
                    String key = req.key();
                    if (key.contains("node-checkpoints")) {
                        return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), checkpointBytes);
                    }
                    // segment file
                    return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), segmentBytes);
                });
    }

    private ListObjectsV2Response singleObjectResponse(String objectKey) {
        S3Object s3Object = S3Object.builder().key(objectKey).build();
        return ListObjectsV2Response.builder()
                .contents(List.of(s3Object))
                .isTruncated(false)
                .build();
    }

    private ListObjectsV2Response emptyResponse() {
        return ListObjectsV2Response.builder()
                .contents(List.of())
                .isTruncated(false)
                .build();
    }

    /** Builds a valid NDJSON line for a WAL entry. */
    private String walLine(long lsn, String key, long version, long expiresAt,
                            List<String> failedNodes, String operationType) throws Exception {
        var node = new java.util.HashMap<String, Object>();
        node.put("lsn", lsn);
        node.put("key", key);
        node.put("version", version);
        node.put("expiresAt", expiresAt);
        node.put("failedNodes", failedNodes);
        node.put("operationType", operationType);
        return objectMapper.writeValueAsString(node);
    }

    // ── getPendingEntries ──────────────────────────────────────────────────────

    @Test
    void getPendingEntries_filtersOutExpiredEntries() throws Exception {
        // Arrange — entry whose expiresAt is in the past
        long pastExpiry = System.currentTimeMillis() - 10_000L;
        String line = walLine(10L, "cache:key1", 1L, pastExpiry, List.of(NODE_ID), "PUT");
        byte[] segmentBytes = line.getBytes(StandardCharsets.UTF_8);

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(singleObjectResponse("wal/lb1/segments/seg-001.ndjson"));
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenAnswer(inv -> {
                    GetObjectRequest req = inv.getArgument(0);
                    if (req.key().contains("node-checkpoints")) {
                        throw NoSuchKeyException.builder().message("not found").build();
                    }
                    return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), segmentBytes);
                });

        // Act
        S3WalReader.WalScanResult result = walReader.getPendingEntries(NODE_ID);

        // Assert
        assertThat(result.pending()).isEmpty();
    }

    @Test
    void getPendingEntries_filtersOutEntriesNotForThisNode() throws Exception {
        // Arrange — entry's failedNodes does not contain NODE_ID
        long futureExpiry = System.currentTimeMillis() + 60_000L;
        String line = walLine(10L, "cache:key1", 1L, futureExpiry, List.of("other-node"), "PUT");
        byte[] segmentBytes = line.getBytes(StandardCharsets.UTF_8);

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(singleObjectResponse("wal/lb1/segments/seg-001.ndjson"));
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenAnswer(inv -> {
                    GetObjectRequest req = inv.getArgument(0);
                    if (req.key().contains("node-checkpoints")) {
                        throw NoSuchKeyException.builder().message("not found").build();
                    }
                    return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), segmentBytes);
                });

        // Act
        S3WalReader.WalScanResult result = walReader.getPendingEntries(NODE_ID);

        // Assert
        assertThat(result.pending()).isEmpty();
    }

    @Test
    void getPendingEntries_filtersOutOldLsn() throws Exception {
        // Arrange — checkpoint at lsn=5, entry lsn=3 => must be excluded
        long futureExpiry = System.currentTimeMillis() + 60_000L;
        String line = walLine(3L, "cache:key1", 1L, futureExpiry, List.of(NODE_ID), "PUT");
        byte[] segmentBytes = line.getBytes(StandardCharsets.UTF_8);

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(singleObjectResponse("wal/lb1/segments/seg-001.ndjson"));
        stubCheckpointLsn(5L, segmentBytes);

        // Act
        S3WalReader.WalScanResult result = walReader.getPendingEntries(NODE_ID);

        // Assert
        assertThat(result.pending()).isEmpty();
    }

    @Test
    void getPendingEntries_filtersOutNonPutOperations() throws Exception {
        // Arrange — operationType is DELETE => must be excluded
        long futureExpiry = System.currentTimeMillis() + 60_000L;
        String line = walLine(10L, "cache:key1", 1L, futureExpiry, List.of(NODE_ID), "DELETE");
        byte[] segmentBytes = line.getBytes(StandardCharsets.UTF_8);

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(singleObjectResponse("wal/lb1/segments/seg-001.ndjson"));
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenAnswer(inv -> {
                    GetObjectRequest req = inv.getArgument(0);
                    if (req.key().contains("node-checkpoints")) {
                        throw NoSuchKeyException.builder().message("not found").build();
                    }
                    return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), segmentBytes);
                });

        // Act
        S3WalReader.WalScanResult result = walReader.getPendingEntries(NODE_ID);

        // Assert
        assertThat(result.pending()).isEmpty();
    }

    @Test
    void getPendingEntries_returnsMatchingEntries() throws Exception {
        // Arrange — entry passes all filters
        long futureExpiry = System.currentTimeMillis() + 60_000L;
        String line = walLine(10L, "cache:key1", 42L, futureExpiry, List.of(NODE_ID), "PUT");
        byte[] segmentBytes = line.getBytes(StandardCharsets.UTF_8);

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(singleObjectResponse("wal/lb1/segments/seg-001.ndjson"));
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenAnswer(inv -> {
                    GetObjectRequest req = inv.getArgument(0);
                    if (req.key().contains("node-checkpoints")) {
                        throw NoSuchKeyException.builder().message("not found").build();
                    }
                    return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), segmentBytes);
                });

        // Act
        S3WalReader.WalScanResult result = walReader.getPendingEntries(NODE_ID);

        // Assert
        assertThat(result.pending()).hasSize(1);
        S3WalReader.WalPendingEntry entry = result.pending().get(0);
        assertThat(entry.key()).isEqualTo("cache:key1");
        assertThat(entry.version()).isEqualTo(42L);
        assertThat(entry.lsn()).isEqualTo(10L);
    }

    // ── saveCheckpoint ─────────────────────────────────────────────────────────

    @Test
    void saveCheckpoint_writesJsonToCorrectS3Key() {
        // Act
        walReader.saveCheckpoint(NODE_ID, 99L);

        // Assert
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(requestCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest captured = requestCaptor.getValue();
        assertThat(captured.bucket()).isEqualTo(BUCKET);
        assertThat(captured.key()).isEqualTo("wal/lb1/node-checkpoints/node1.json");
        assertThat(captured.contentType()).isEqualTo("application/json");
        assertThat(captured.serverSideEncryptionAsString()).isEqualTo("AES256");
    }

    @Test
    void saveCheckpoint_skipsNegativeLsn() {
        // Act — negative lsn must be a no-op
        walReader.saveCheckpoint(NODE_ID, -1L);

        // Assert
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    // ── loadCheckpoint (via getPendingEntries) ─────────────────────────────────

    @Test
    void loadCheckpoint_returnsZeroWhenNoCheckpointExists() throws Exception {
        // Arrange — no segments at all, checkpoint throws NoSuchKeyException
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(emptyResponse());
        // getObjectAsBytes is not expected to be called when there are no segments,
        // but loadCheckpoint calls it first — stub to throw NoSuchKeyException
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        // Act — no exception must be thrown; empty list returned
        S3WalReader.WalScanResult result = walReader.getPendingEntries(NODE_ID);

        // Assert — internally loadCheckpoint returned 0 (no crash, no entries)
        assertThat(result.pending()).isEmpty();
        // verify getObjectAsBytes was called for the checkpoint key
        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(captor.capture());
        assertThat(captor.getValue().key()).isEqualTo("wal/lb1/node-checkpoints/node1.json");
    }

    // ── WalScanResult (EPMICMPHE-246) ─────────────────────────────────────────

    /**
     * getPendingEntries must return a WalScanResult wrapping both the pending list
     * and the maxScannedLsn — not a raw List.
     */
    @Test
    void getPendingEntries_returnsWalScanResult() throws Exception {
        // Arrange — one matching entry at lsn=15
        long futureExpiry = System.currentTimeMillis() + 60_000L;
        String line = walLine(15L, "cache:result-key", 1L, futureExpiry, List.of(NODE_ID), "PUT");
        byte[] segmentBytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(singleObjectResponse("wal/lb1/segments/seg-010.ndjson"));
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenAnswer(inv -> {
                    GetObjectRequest req = inv.getArgument(0);
                    if (req.key().contains("node-checkpoints")) {
                        throw NoSuchKeyException.builder().message("not found").build();
                    }
                    return software.amazon.awssdk.core.ResponseBytes.fromByteArray(
                            GetObjectResponse.builder().build(), segmentBytes);
                });

        // Act — returns WalScanResult, not List
        S3WalReader.WalScanResult result = walReader.getPendingEntries(NODE_ID);

        // Assert
        assertThat(result.pending()).hasSize(1);
        assertThat(result.pending().get(0).key()).isEqualTo("cache:result-key");
        assertThat(result.maxScannedLsn()).isEqualTo(15L);
    }

    /**
     * maxScannedLsn must equal the highest LSN seen across ALL lines in all segments,
     * even when that line's failedNodes does not contain the nodeId (i.e., was not
     * added to the pending list).
     */
    @Test
    void getPendingEntries_maxScannedLsn_trackHighestLsnEvenWhenLineFiltered() throws Exception {
        // Arrange — two lines:
        //   lsn=5  for NODE_ID  (matches — goes into pending)
        //   lsn=99 for other-node (does NOT match nodeId — filtered out, but lsn still tracked)
        long futureExpiry = System.currentTimeMillis() + 60_000L;
        String line1 = walLine(5L,  "cache:key-match",  1L, futureExpiry, List.of(NODE_ID),     "PUT");
        String line2 = walLine(99L, "cache:key-nonode", 1L, futureExpiry, List.of("other-node"), "PUT");
        byte[] segmentBytes = (line1 + "\n" + line2).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(singleObjectResponse("wal/lb1/segments/seg-020.ndjson"));
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenAnswer(inv -> {
                    GetObjectRequest req = inv.getArgument(0);
                    if (req.key().contains("node-checkpoints")) {
                        throw NoSuchKeyException.builder().message("not found").build();
                    }
                    return software.amazon.awssdk.core.ResponseBytes.fromByteArray(
                            GetObjectResponse.builder().build(), segmentBytes);
                });

        // Act
        S3WalReader.WalScanResult result = walReader.getPendingEntries(NODE_ID);

        // Assert — only line1 is pending, but maxScannedLsn reflects line2's lsn=99
        assertThat(result.pending()).hasSize(1);
        assertThat(result.pending().get(0).key()).isEqualTo("cache:key-match");
        assertThat(result.maxScannedLsn()).isEqualTo(99L);
    }

    /**
     * When no segments exist at all, maxScannedLsn must be 0.
     */
    @Test
    void getPendingEntries_maxScannedLsnIsZeroWhenNoSegments() throws Exception {
        // Arrange — no segments in S3
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(emptyResponse());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        // Act
        S3WalReader.WalScanResult result = walReader.getPendingEntries(NODE_ID);

        // Assert
        assertThat(result.pending()).isEmpty();
        assertThat(result.maxScannedLsn()).isEqualTo(0L);
    }
}
