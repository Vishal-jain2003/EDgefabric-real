package com.edgefabric.loadbalancer.wal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class WalSegmentFlusher extends AbstractWalFlusher {

    private static final Logger log = LoggerFactory.getLogger(WalSegmentFlusher.class);

    private final S3Client s3;

    public WalSegmentFlusher(S3Client s3, WalProperties props, ObjectMapper mapper) {
        super(recoverLsnFromS3(s3, props, mapper), props, mapper);
        this.s3 = s3;
    }

    private static long recoverLsnFromS3(S3Client s3, WalProperties props, ObjectMapper mapper) {
        String key = checkpointKeyFor(props.getLbId());
        try {
            byte[] bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(props.getS3Bucket())
                            .key(key)
                            .build()
            ).asByteArray();
            CheckpointState state = mapper.readValue(bytes, CheckpointState.class);
            log.info("WAL checkpoint recovered | lbId={} lastCommittedLsn={}",
                    props.getLbId(), state.lastCommittedLsn());
            return state.lastCommittedLsn() + 1;
        } catch (NoSuchKeyException e) {
            log.info("No WAL checkpoint found — starting LSN from 0. lbId={}", props.getLbId());
            return 0L;
        } catch (Exception e) {
            log.warn("WAL checkpoint read failed, starting LSN from 0. lbId={} error={}",
                    props.getLbId(), e.getMessage());
            return 0L;
        }
    }

    @Override
    protected void writeSegment(String key, byte[] bytes) {
        int attempt   = 0;
        long backoffMs = props.getRetryBackoffMs();

        while (attempt < props.getMaxFlushRetries()) {
            try {
                putToS3(key, bytes, "application/x-ndjson");
                log.info("WAL segment written key={} bytes={}", key, bytes.length);
                return;
            } catch (Exception e) {
                attempt++;
                log.warn("WAL S3 write failed (attempt {}/{}) key={}: {}",
                        attempt, props.getMaxFlushRetries(), key, e.getMessage());
                if (attempt < props.getMaxFlushRetries()) {
                    sleep(backoffMs);
                    backoffMs *= 2;
                }
            }
        }
        log.error("WAL segment dead-lettered after {} retries key={}", props.getMaxFlushRetries(), key);
        deadLetterCount.incrementAndGet();
    }

    @Override
    protected void persistCheckpoint(long lastLsn) {
        try {
            putToS3(checkpointKey(), buildCheckpointJson(lastLsn).getBytes(StandardCharsets.UTF_8), "application/json");
        } catch (Exception e) {
            log.warn("WAL checkpoint write failed (non-fatal) lastLsn={}: {}", lastLsn, e.getMessage());
        }
    }

    private void putToS3(String key, byte[] bytes, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(props.getS3Bucket())
                        .key(key)
                        .contentType(contentType)
                        .serverSideEncryption("AES256")
                        .build(),
                RequestBody.fromBytes(bytes)
        );
    }

    @Override
    public void replay(java.util.function.Consumer<WalEntry> handler) {
        log.info("Starting efficient WAL replay from S3...");
        String prefix = "wal/" + props.getLbId() + "/segments/";
        try {
            ListObjectsV2Request req = ListObjectsV2Request.builder()
                    .bucket(props.getS3Bucket())
                    .prefix(prefix)
                    .build();

            // AC9: Perform a single probe to detect an empty segment list before entering the loop.
            // S3 lifecycle policies may delete all segments — that is a normal, non-error condition.
            ListObjectsV2Response probe = s3.listObjectsV2(req);
            if (probe.contents().isEmpty() && !Boolean.TRUE.equals(probe.isTruncated())) {
                log.info("WAL replay: no segments found in S3 (S3 lifecycle may have deleted them). 0 entries replayed.");
                return;
            }

            ListObjectsV2Response response = probe;
            do {
                for (S3Object object : response.contents()) {
                    log.info("Replaying segment {}", object.key());
                    byte[] bytes = s3.getObjectAsBytes(
                            GetObjectRequest.builder()
                                    .bucket(props.getS3Bucket())
                                    .key(object.key())
                                    .build()
                    ).asByteArray();

                    String content = new String(bytes, StandardCharsets.UTF_8);
                    for (String line : content.split("\n")) {
                        if (line.trim().isEmpty()) continue;
                        try {
                            SegmentLine sl = mapper.readValue(line, SegmentLine.class);
                            byte[] data = sl.data() != null ? Base64.getDecoder().decode(sl.data()) : null;
                            OperationType opType = OperationType.valueOf(sl.operationType());

                            // Handle legacy entries (pre-enhanced WAL) and new entries
                            java.util.Set<String> successfulNodes = sl.successfulNodes() != null
                                ? new java.util.HashSet<>(sl.successfulNodes())
                                : java.util.Set.of();
                            java.util.Set<String> failedNodes = sl.failedNodes() != null
                                ? new java.util.HashSet<>(sl.failedNodes())
                                : java.util.Set.of();

                            WalEntry entry = new WalEntry(
                                sl.key(), data, sl.expiresAt(), sl.contentType(),
                                sl.version(), opType, sl.timestamp(),
                                successfulNodes, failedNodes
                            );
                            handler.accept(entry);
                        } catch (Exception e) {
                            log.error("Failed to parse WAL line: {}", line, e);
                        }
                    }
                }
                if (Boolean.TRUE.equals(response.isTruncated())) {
                    req = req.toBuilder().continuationToken(response.nextContinuationToken()).build();
                    response = s3.listObjectsV2(req);
                }
            } while (Boolean.TRUE.equals(response.isTruncated()));
        } catch (Exception e) {
            log.error("Replay from S3 failed: {}", e.getMessage(), e);
            throw new RuntimeException("S3 Replay failed", e);
        }
        log.info("Replay complete.");
    }
}
