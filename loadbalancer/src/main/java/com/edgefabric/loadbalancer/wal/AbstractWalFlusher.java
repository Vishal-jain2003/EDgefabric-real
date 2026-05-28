package com.edgefabric.loadbalancer.wal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared async-flush engine for all WAL backends (S3 and local).
 *
 * <h3>Buffer design — no-block guarantee</h3>
 * <pre>
 *   Queue capacity  =  flushBatchSize  +  bufferHeadroom
 *                         (100)               (50)   = 150 by default
 * </pre>
 * Each flush cycle drains at most {@code flushBatchSize} entries via
 * {@link BlockingQueue#drainTo(java.util.Collection, int)}, which is O(n) and never blocks the
 * caller.  The {@code bufferHeadroom} slots remain free while the backend write
 * (S3 PutObject, local disk) is in progress, so concurrent {@link #append} calls
 * always find room in the queue and never experience head-of-line blocking.
 *
 * <h3>Timestamp ordering</h3>
 * Entries in each batch are sorted by {@code WalEntry#timestamp()} before
 * serialisation.  This means every segment file stored in S3 (or on disk) contains
 * chronologically ordered NDJSON lines, enabling efficient time-range replay.
 *
 * <h3>Segment key format</h3>
 * <pre>
 *   wal/&lt;lbId&gt;/segments/&lt;firstEntryTimestampMs&gt;_&lt;lsn10d&gt;.wal
 *   wal/&lt;lbId&gt;/checkpoint.json
 * </pre>
 * The timestamp prefix gives lexicographic == chronological ordering in S3 object listings.
 * The zero-padded LSN suffix is a tie-breaker for entries flushed within the same millisecond.
 *
 * <h3>Checkpoint correctness in a multi-threaded append path</h3>
 * LSNs are assigned by {@link AtomicLong#getAndIncrement()} before {@link BlockingQueue#offer}.
 * Under contention two threads can enqueue entries out of LSN order.
 * {@code persistCheckpoint} therefore records {@code max(lsn)} across the entire
 * drained batch, not just the last element after sorting — guaranteeing that the
 * recovered start-LSN is always above every entry that was committed in this cycle.
 */
abstract class AbstractWalFlusher implements WalWriter, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AbstractWalFlusher.class);

    protected final WalProperties props;
    protected final ObjectMapper mapper;
    /** Monotonically increasing per-LB sequence; recovered from checkpoint on startup. */
    private final AtomicLong lsn;
    /**
     * Bounded queue.  Capacity = flushBatchSize + bufferHeadroom ensures
     * that appenders never block while the flusher is writing to the backend.
     */
    private final BlockingQueue<StampedEntry> buffer;
    private final ScheduledExecutorService flushExecutor;
    private final AtomicBoolean isFlushing = new AtomicBoolean(false);
    /** Counts entries/segments that could not be persisted after all retries. */
    protected final AtomicLong deadLetterCount = new AtomicLong(0);

    /**
     * @param initialLsn first LSN to assign — subclass recovers this from its checkpoint.
     */
    protected AbstractWalFlusher(long initialLsn, WalProperties props, ObjectMapper mapper) {
        this.props  = props;
        this.mapper = mapper;
        this.lsn    = new AtomicLong(initialLsn);

        int capacity = props.getFlushBatchSize() + props.getBufferHeadroom();
        this.buffer  = new LinkedBlockingQueue<>(capacity);

        this.flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wal-flusher");
            t.setDaemon(true);
            return t;
        });

        if (props.getFlushIntervalMs() > 0) {
            this.flushExecutor.scheduleAtFixedRate(this::triggerFlush,
                    props.getFlushIntervalMs(), props.getFlushIntervalMs(), TimeUnit.MILLISECONDS);
        }
    }

    // ─── WalWriter ───────────────────────────────────────────────────────────

    /**
     * Assigns the next LSN and offers the entry to the in-memory queue.
     * Never blocks; if the queue is full the entry is dead-lettered.
     */
    @Override
    public void append(WalEntry entry) {
        long assignedLsn = lsn.getAndIncrement();
        if (!buffer.offer(new StampedEntry(assignedLsn, entry))) {
            log.error("WAL buffer full — dead-lettering entry lsn={} key={}", assignedLsn, entry.key());
            deadLetterCount.incrementAndGet();
        }

        if (props.getFlushIntervalMs() > 0 && buffer.size() >= props.getFlushBatchSize()) {
            triggerFlush();
        }
    }

    private void triggerFlush() {
        if (isFlushing.compareAndSet(false, true)) {
            flushExecutor.submit(() -> {
                try {
                    flushCycle();
                } finally {
                    isFlushing.set(false);
                    // Re-check in case the buffer filled up again while flushing was in progress
                    if (buffer.size() >= props.getFlushBatchSize()) {
                        triggerFlush();
                    }
                }
            });
        }
    }

    public long getDeadLetterCount() {
        return deadLetterCount.get();
    }

    @Override
    public int getPendingCount() {
        return buffer.size();
    }

    // ─── Flush cycle ────────────────────────────────────────────────────────

    /**
     * Called periodically by the background scheduler and once more on shutdown.
     *
     * <ol>
     *   <li>Drain up to {@code flushBatchSize} entries (bounded — never blocks).</li>
     *   <li>Sort by {@code timestamp} for chronological segment files.</li>
     *   <li>Serialise to ≤{@code segmentSizeBytes} NDJSON chunks, tracking entry
     *       counts to avoid a post-hoc byte scan.</li>
     *   <li>Delegate each chunk to {@link #writeSegment}.</li>
     *   <li>Update checkpoint with {@code max(lsn)} in this batch.</li>
     * </ol>
     */
    void flushCycle() {
        List<StampedEntry> batch = new ArrayList<>(props.getFlushBatchSize());
        buffer.drainTo(batch, props.getFlushBatchSize());
        if (batch.isEmpty()) return;

        // Compute max LSN before reordering (concurrent appenders may enqueue
        // out of LSN order, so the last-drained element is not guaranteed to be max).
        long maxLsn = batch.stream().mapToLong(StampedEntry::lsn).max().orElseThrow();

        // Sort by entry timestamp so segment files are chronologically ordered.
        batch.sort(Comparator.comparingLong(e -> e.entry().timestamp()));

        List<SegmentSlice> slices = serialiseToSegments(batch);

        int batchOffset = 0;
        for (SegmentSlice slice : slices) {
            long segFirstLsn = batch.get(batchOffset).lsn();
            long segFirstTs  = batch.get(batchOffset).entry().timestamp();
            writeSegment(segmentKey(segFirstLsn, segFirstTs), slice.bytes());
            batchOffset += slice.entryCount();
        }

        persistCheckpoint(maxLsn);
    }

    // ─── Key helpers ────────────────────────────────────────────────────────

    /**
     * Returns {@code wal/<lbId>/segments/<firstTimestampMs>_<lsn10d>.wal}.
     * Lexicographic sort of S3 object keys == chronological order.
     */
    String segmentKey(long firstLsn, long firstTimestampMs) {
        return String.format("wal/%s/segments/%d_%010d.wal",
                props.getLbId(), firstTimestampMs, firstLsn);
    }

    protected String checkpointKey() {
        return checkpointKeyFor(props.getLbId());
    }

    /** Static variant so subclass static recovery methods can reuse the same key formula. */
    static String checkpointKeyFor(String lbId) {
        return "wal/" + lbId + "/checkpoint.json";
    }

    // ─── Serialisation ──────────────────────────────────────────────────────

    /**
     * Splits {@code entries} into ≤{@code segmentSizeBytes} NDJSON chunks.
     * Each {@link SegmentSlice} carries the serialised bytes AND the exact entry
     * count for that chunk, avoiding a post-hoc byte scan to track batch offsets.
     */
    List<SegmentSlice> serialiseToSegments(List<StampedEntry> entries) {
        List<SegmentSlice> slices = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();
        int entryCount = 0;

        for (StampedEntry stamped : entries) {
            byte[] line = serialiseLine(stamped);
            if (current.size() + line.length > props.getSegmentSizeBytes() && current.size() > 0) {
                slices.add(new SegmentSlice(current.toByteArray(), entryCount));
                current    = new ByteArrayOutputStream();
                entryCount = 0;
            }
            current.writeBytes(line);
            if (line.length > 0) entryCount++;  // empty on serialisation error; skip offset
        }
        if (current.size() > 0) {
            slices.add(new SegmentSlice(current.toByteArray(), entryCount));
        }
        return slices;
    }

    private byte[] serialiseLine(StampedEntry stamped) {
        WalEntry e = stamped.entry();
        try {
            SegmentLine line = new SegmentLine(
                    stamped.lsn(),
                    e.timestamp(),
                    e.operationType().name(),
                    e.key(),
                    e.expiresAt(),
                    e.contentType(),
                    e.data() != null ? Base64.getEncoder().encodeToString(e.data()) : null,
                    e.version(),
                    e.successfulNodes() != null ? List.copyOf(e.successfulNodes()) : List.of(),
                    e.failedNodes() != null ? List.copyOf(e.failedNodes()) : List.of()
            );
            return (mapper.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error("WAL serialisation failed lsn={} key={}", stamped.lsn(), e.key(), ex);
            return new byte[0];
        }
    }

    // ─── Checkpoint JSON ────────────────────────────────────────────────────

    /**
     * Builds the checkpoint JSON using the shared {@link CheckpointState} record and
     * the injected {@link ObjectMapper}.  Both S3 and local backends call this to
     * guarantee a consistent format.
     */
    protected String buildCheckpointJson(long lastLsn) {
        try {
            return mapper.writeValueAsString(new CheckpointState(lastLsn, System.currentTimeMillis()));
        } catch (Exception e) {
            // ObjectMapper cannot fail on a plain record with primitive fields; fall back.
            return "{\"lastCommittedLsn\":" + lastLsn
                    + ",\"updatedAt\":" + System.currentTimeMillis() + "}";
        }
    }

    // ─── Abstract persistence ────────────────────────────────────────────────

    /**
     * Persist a serialised segment at {@code key}.
     * Implementations should retry internally; on permanent failure they should
     * increment {@link #deadLetterCount} and log an ERROR.
     */
    protected abstract void writeSegment(String key, byte[] bytes);

    /**
     * Atomically overwrite the checkpoint file with {@code lastCommittedLsn}.
     * A failure here is non-fatal — the WAL can replay from an earlier point.
     */
    protected abstract void persistCheckpoint(long lastLsn);

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Graceful shutdown:
     * <ol>
     *   <li>Stop accepting new scheduled tasks.</li>
     *   <li>Wait up to 10 s for the currently running flush to complete — this covers
     *       the worst-case retry path: 3 retries × 500ms base backoff (doubling) +
     *       per-attempt S3 latency ≈ 6–7 s total.</li>
     *   <li>Run one final {@link #flushCycle} on the calling thread to drain any
     *       entries that arrived between the last scheduled cycle and now.</li>
     * </ol>
     */
    @Override
    public void destroy() {
        log.info("WAL flusher shutting down — flushing remaining entries. lbId={}", props.getLbId());
        flushExecutor.shutdown();
        try {
            if (!flushExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("WAL scheduler did not terminate cleanly in 10 s — forcing shutdown");
                flushExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        } catch (InterruptedException ie) {
            flushExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Final drain runs on the calling thread; no scheduled cycles can overlap.
        flushCycle();
    }

    // ─── Utilities ──────────────────────────────────────────────────────────

    protected void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── Inner types ────────────────────────────────────────────────────────

    /** A WAL entry paired with its assigned sequence number. */
    public record StampedEntry(long lsn, WalEntry entry) {}

    /** Serialised segment bytes plus the number of WAL entries it contains. */
    record SegmentSlice(byte[] bytes, int entryCount) {}

    protected record CheckpointState(
            @JsonProperty("lastCommittedLsn") long lastCommittedLsn,
            @JsonProperty("updatedAt") long updatedAt
    ) {}

    record SegmentLine(
            @JsonProperty("lsn") long lsn,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("operationType") String operationType,
            @JsonProperty("key") String key,
            @JsonProperty("expiresAt") long expiresAt,
            @JsonProperty("contentType") String contentType,
            @JsonProperty("data") String data,
            @JsonProperty("version") long version,
            @JsonProperty("successfulNodes") List<String> successfulNodes,
            @JsonProperty("failedNodes") List<String> failedNodes
    ) {}
}
