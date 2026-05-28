package com.edgefabric.caching.antiEntropy;

import com.edgefabric.caching.config.WalReaderProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class S3WalReader {

    public record WalPendingEntry(String key, long version, long lsn) {}

    /**
     * Result of a WAL segment scan.
     *
     * <p>Wraps the pending-entry list and the highest LSN seen across <em>all</em>
     * parsed lines — even those filtered out by node-id, expiry, or operationType.
     * Uses composition rather than inheritance to avoid inheriting the full mutable
     * {@link java.util.ArrayList} API and breaking {@code equals}/{@code hashCode}.
     */
    public static final class WalScanResult {
        private final List<WalPendingEntry> pending;
        private final long maxScannedLsn;

        public WalScanResult(List<WalPendingEntry> pending, long maxScannedLsn) {
            this.pending = List.copyOf(pending);
            this.maxScannedLsn = maxScannedLsn;
        }

        /** Returns an unmodifiable view of the pending entries. */
        public List<WalPendingEntry> pending() {
            return pending;
        }

        /** Returns the highest LSN seen across all scanned WAL lines. */
        public long maxScannedLsn() {
            return maxScannedLsn;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SegmentLine(
            long lsn,
            String key,
            long version,
            long expiresAt,
            List<String> failedNodes,
            String operationType
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NodeCheckpoint(long lastRepairedLsn, long updatedAt) {}

    private final S3Client s3;
    private final WalReaderProperties props;
    private final ObjectMapper objectMapper;

    public S3WalReader(S3Client s3, WalReaderProperties props, ObjectMapper objectMapper) {
        this.s3 = s3;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public WalScanResult getPendingEntries(String nodeId) {
        long lastRepairedLsn = loadCheckpoint(nodeId);
        long now = System.currentTimeMillis();
        String prefix = "wal/" + props.getLbId() + "/segments/";
        List<WalPendingEntry> pending = new ArrayList<>();
        long maxScannedLsn = 0L;

        try {
            ListObjectsV2Request req = ListObjectsV2Request.builder()
                    .bucket(props.getS3Bucket())
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response response = s3.listObjectsV2(req);
            if (response.contents().isEmpty() && !Boolean.TRUE.equals(response.isTruncated())) {
                log.info("WAL reader: no segments found in S3 for lbId={}", props.getLbId());
                return new WalScanResult(pending, maxScannedLsn);
            }

            while (true) {
                for (S3Object object : response.contents()) {
                    byte[] bytes = s3.getObjectAsBytes(
                            GetObjectRequest.builder()
                                    .bucket(props.getS3Bucket())
                                    .key(object.key())
                                    .build()
                    ).asByteArray();

                    String content = new String(bytes, StandardCharsets.UTF_8);
                    for (String line : content.split("\n")) {
                        if (line.isBlank()) continue;
                        try {
                            SegmentLine sl = objectMapper.readValue(line, SegmentLine.class);
                            // Track maxScannedLsn for ALL parsed lines, regardless of filters
                            if (sl.lsn() > maxScannedLsn) {
                                maxScannedLsn = sl.lsn();
                            }
                            if (sl.lsn() <= lastRepairedLsn) continue;
                            if (sl.failedNodes() == null || !sl.failedNodes().contains(nodeId)) continue;
                            if (sl.expiresAt() <= now) continue;
                            if (!"PUT".equals(sl.operationType())) continue;

                            pending.add(new WalPendingEntry(sl.key(), sl.version(), sl.lsn()));
                        } catch (Exception e) {
                            log.warn("WAL reader: failed to parse segment line in {}: {}", object.key(), e.getMessage());
                        }
                    }
                }

                if (!Boolean.TRUE.equals(response.isTruncated())) {
                    break;
                }
                req = req.toBuilder().continuationToken(response.nextContinuationToken()).build();
                response = s3.listObjectsV2(req);
            }

        } catch (Exception e) {
            log.error("WAL reader: failed to read segments from S3 for lbId={}: {}", props.getLbId(), e.getMessage(), e);
        }

        return new WalScanResult(pending, maxScannedLsn);
    }

    public void saveCheckpoint(String nodeId, long lsn) {
        if (lsn < 0) return;
        String key = "wal/" + props.getLbId() + "/node-checkpoints/" + nodeId + ".json";
        try {
            String json = objectMapper.writeValueAsString(
                    Map.of("lastRepairedLsn", lsn, "updatedAt", System.currentTimeMillis())
            );
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.getS3Bucket())
                            .key(key)
                            .contentType("application/json")
                            .serverSideEncryption("AES256")
                            .build(),
                    RequestBody.fromString(json)
            );
            log.info("WAL reader: checkpoint saved nodeId={} lsn={}", nodeId, lsn);
        } catch (Exception e) {
            log.warn("WAL reader: failed to save checkpoint nodeId={} lsn={}: {}", nodeId, lsn, e.getMessage());
        }
    }

    private long loadCheckpoint(String nodeId) {
        String key = "wal/" + props.getLbId() + "/node-checkpoints/" + nodeId + ".json";
        try {
            byte[] bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(props.getS3Bucket())
                            .key(key)
                            .build()
            ).asByteArray();
            NodeCheckpoint checkpoint = objectMapper.readValue(bytes, NodeCheckpoint.class);
            return checkpoint.lastRepairedLsn();
        } catch (NoSuchKeyException e) {
            log.info("WAL reader: no checkpoint found for nodeId={}, starting from LSN 0", nodeId);
            return 0L;
        } catch (Exception e) {
            log.warn("WAL reader: failed to load checkpoint for nodeId={}: {}", nodeId, e.getMessage());
            return 0L;
        }
    }
}
