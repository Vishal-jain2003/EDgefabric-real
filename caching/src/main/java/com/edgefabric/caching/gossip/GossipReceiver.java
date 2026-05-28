package com.edgefabric.caching.gossip;

import com.edgefabric.caching.dto.GossipDigestDTO;
import com.edgefabric.caching.dto.GossipDigestDTO.GossipNodeEntry;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Arrays;

/**
 * Receives gossip UDP packets on the dedicated gossip socket and merges
 * incoming membership state into the local {@link MembershipList}.
 *
 * <p>Packets are decoded by {@link GossipMessageCodec} (compact binary + LZ4).
 * FULL entries include complete network coordinates; DELTA entries carry only
 * {@code cacheNodeId + state} — the receiver enriches them from its local store.
 */
@Slf4j
@Component
@Profile("!test")
public class GossipReceiver {

    /** Must be ≥ the maximum compressed UDP payload size. */
    static final int RECEIVE_BUFFER_SIZE = 65535;

    private final DatagramSocket     datagramSocket;
    private final GossipMessageCodec codec;
    private final MembershipList     membershipList;
    private final Tracer             tracer;

    private volatile boolean running = true;

    public GossipReceiver(DatagramSocket datagramSocket,
                          GossipMessageCodec codec,
                          MembershipList membershipList,
                          Tracer tracer) {
        this.datagramSocket = datagramSocket;
        this.codec          = codec;
        this.membershipList = membershipList;
        this.tracer         = tracer;
    }

    @PostConstruct
    public void start() {
        Thread receiverThread = new Thread(this::receiveLoop, "gossip-receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();
        log.info("Gossip receiver started on port {}", datagramSocket.getLocalPort());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (!datagramSocket.isClosed()) {
            datagramSocket.close();
        }
        log.info("Gossip receiver stopping");
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                datagramSocket.receive(packet);

                // Copy only the live bytes — buffer may be larger than the packet
                byte[] data = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());
                GossipDigestDTO digest = codec.decode(data);
                processDigest(digest);

            } catch (SocketException e) {
                if (running) {
                    log.error("Socket error in gossip receiver", e);
                }
            } catch (Exception e) {
                log.warn("Error processing gossip packet: {}", e.getMessage());
            }
        }

        log.info("Gossip receiver loop exited");
    }

    private void processDigest(GossipDigestDTO digest) {
        if (digest == null || digest.getEntries() == null || digest.getEntries().isEmpty()) {
            log.debug("Received empty gossip packet, ignoring");
            return;
        }

        Span span = tracer.nextSpan().name("gossip.receive");
        try (@SuppressWarnings("unused") Tracer.SpanInScope scope = tracer.withSpan(span.start())) {
            span.tag("source.node", digest.getSenderNodeId());
            span.tag("entry.count", String.valueOf(digest.getEntries().size()));

            log.debug("Received gossip from {} with {} entries",
                    digest.getSenderNodeId(), digest.getEntries().size());

            for (GossipNodeEntry entry : digest.getEntries()) {
                mergeEntry(entry);
            }
        } finally {
            span.end();
        }
    }

    /**
     * Merges one gossip entry into the local membership list.
     *
     * <ul>
     *   <li><b>FULL entry</b> ({@code host != null}) — reconstructs a complete
     *       {@link NodeInfo} and merges it directly.</li>
     *   <li><b>DELTA entry</b> ({@code host == null}) — looks up the existing
     *       node in the local store to obtain stable network coordinates, then
     *       applies the incoming mutable state (status / incarnation / heartbeat).
     *       If the node is not yet in the local store the entry is silently skipped;
     *       a FULL entry from any peer will arrive within the next few gossip cycles.</li>
     * </ul>
     */
    private void mergeEntry(GossipNodeEntry entry) {
        try {
            NodeInfo incoming;
            if (entry.isFullEntry()) {
                incoming = entry.toNodeInfo();
            } else {
                // DELTA path — enrich with existing network coordinates
                NodeInfo existing = membershipList.getNode(entry.getCacheNodeId());
                if (existing == null) {
                    log.debug("Skipping DELTA entry for unknown node '{}'; "
                            + "a FULL entry is expected within the next gossip cycle",
                            entry.getCacheNodeId());
                    return;
                }
                incoming = entry.toNodeInfo(existing);
            }
            membershipList.merge(incoming);
        } catch (Exception e) {
            log.warn("Failed to merge gossip entry for node '{}': {}",
                    entry.getCacheNodeId(), e.getMessage());
        }
    }
}
