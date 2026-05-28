package com.edgefabric.loadbalancer.wal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

/**
 * Local-filesystem WAL flusher — use when {@code wal.storage=local}.
 */
public class LocalWalWriter extends AbstractWalFlusher {

    private static final Logger log = LoggerFactory.getLogger(LocalWalWriter.class);

    private final Path baseDir;

    public LocalWalWriter(WalProperties props, ObjectMapper mapper) {
        super(recoverLsnFromLocal(props, mapper), props, mapper);
        this.baseDir = Path.of(props.getLocalDir());
        log.info("Local WAL writer ready | lbId={} dir={}", props.getLbId(), baseDir.toAbsolutePath());
    }

    private static long recoverLsnFromLocal(WalProperties props, ObjectMapper mapper) {
        Path file = Path.of(props.getLocalDir()).resolve(checkpointKeyFor(props.getLbId()));
        try {
            byte[] bytes = Files.readAllBytes(file);
            CheckpointState state = mapper.readValue(bytes, CheckpointState.class);
            log.info("Local WAL checkpoint recovered | lbId={} lastCommittedLsn={}",
                    props.getLbId(), state.lastCommittedLsn());
            return state.lastCommittedLsn() + 1;
        } catch (NoSuchFileException e) {
            log.info("No local WAL checkpoint — starting LSN from 0. lbId={}", props.getLbId());
            return 0L;
        } catch (Exception e) {
            log.warn("Local WAL checkpoint read failed, starting LSN from 0. error={}", e.getMessage());
            return 0L;
        }
    }

    @Override
    protected void writeSegment(String key, byte[] bytes) {
        Path target = baseDir.resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            log.info("WAL segment written locally path={} bytes={}", target, bytes.length);
        } catch (IOException e) {
            log.error("Local WAL segment write failed path={}: {}", target, e.getMessage());
            deadLetterCount.incrementAndGet();
        }
    }

    @Override
    protected void persistCheckpoint(long lastLsn) {
        Path file = baseDir.resolve(checkpointKey());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, buildCheckpointJson(lastLsn),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("Local WAL checkpoint write failed (non-fatal) lastLsn={}: {}", lastLsn, e.getMessage());
        }
    }

    @Override
    public void replay(java.util.function.Consumer<WalEntry> handler) {
        log.info("Starting efficient local WAL replay...");
        Path segmentsDir = baseDir.resolve("wal/" + props.getLbId() + "/segments");
        if (!Files.exists(segmentsDir)) {
            log.info("No local segments directory found at {}. Replay complete.", segmentsDir);
            return;
        }
        
        try (Stream<Path> files = Files.walk(segmentsDir)) {
            files.filter(Files::isRegularFile)
                 .sorted() // alphabetical sort is chronological for our keys
                 .forEach(file -> {
                     log.info("Replaying segment {}", file.getFileName());
                     try {
                         List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                         for (String line : lines) {
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
                                 log.error("Failed to parse WAL line in file {}: {}", file, line, e);
                             }
                         }
                     } catch (IOException e) {
                         log.error("Failed to read WAL file {}", file, e);
                     }
                 });
        } catch (IOException e) {
            log.error("Replay from local storage failed", e);
            throw new RuntimeException("Local Replay failed", e);
        }
        log.info("Local replay complete.");
    }
}
