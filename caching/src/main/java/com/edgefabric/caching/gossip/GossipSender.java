package com.edgefabric.caching.gossip;

import com.edgefabric.caching.config.GossipProperties;
import com.edgefabric.caching.dto.GossipDigestDTO;
import com.edgefabric.caching.dto.GossipDigestDTO.GossipNodeEntry;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@Profile("!test")
public class GossipSender {

    static final int UDP_MTU = 1400;

    private final MembershipList     membershipList;
    private final GossipProperties   gossipProperties;
    private final DatagramSocket     datagramSocket;
    private final GossipMessageCodec codec;

    public GossipSender(MembershipList membershipList,
                        GossipProperties gossipProperties,
                        DatagramSocket datagramSocket,
                        GossipMessageCodec codec) {
        this.membershipList   = membershipList;
        this.gossipProperties = gossipProperties;
        this.datagramSocket   = datagramSocket;
        this.codec            = codec;
    }

    @Observed(name = "gossip.send", contextualName = "gossip-send-round")
    @Scheduled(fixedDelayString = "${gossip.message-interval-ms:1000}")
    public void sendGossip() {

        // 1. Bump self heartbeat so self is in the dirty digest
        membershipList.bumpSelfHeartbeat();

        // 2. Pick random alive/suspect peers — skip round if cluster is empty
        List<NodeInfo> peers = membershipList.getRandomPeers(gossipProperties.getFanout());
        if (peers.isEmpty()) {
            log.debug("No peers available for gossip");
            return;
        }

        // 3. Build dirty entries — FULL for new nodes (low heartbeat), DELTA for established ones
        List<GossipNodeEntry> entries = membershipList.getDirtyDigestAndClear().stream()
                .map(node -> node.getHeartbeat() < GossipMessageCodec.FULL_ENTRY_HEARTBEAT_THRESHOLD
                        ? GossipNodeEntry.fromNodeInfo(node)
                        : GossipNodeEntry.fromNodeInfoDelta(node))
                .toList();

        if (entries.isEmpty()) {
            log.debug("No dirty entries to send");
            return;
        }

        // 4. Build digest with the sender's own heartbeat as the delta reference
        GossipDigestDTO digest = GossipDigestDTO.builder()
                .senderNodeId(membershipList.getSelf().getCacheNodeId())
                .senderHeartbeat(membershipList.getSelf().getHeartbeat())
                .entries(entries)
                .build();

        // 5. Encode once with MTU enforcement — reuse byte[]s for all peers
        List<byte[]> packets;
        try {
            packets = serializeWithMtuEnforcement(digest);
        } catch (IOException e) {
            log.error("Failed to encode gossip digest: {}", e.getMessage());
            restoreDirtyEntries(entries);
            return;
        }

        // 6. Fan-out to selected peers
        int successCount = sendPacketsToPeers(packets, peers);

        if (successCount == 0) {
            restoreDirtyEntries(entries);
        }

        log.debug("Gossip complete: sent {} entries ({} packet(s)) to {}/{} peers",
                entries.size(), packets.size(), successCount, peers.size());
    }

    // ── MTU enforcement ───────────────────────────────────────────────────────

    /**
     * Encodes the digest into one or more byte arrays each ≤ {@link #UDP_MTU}.
     *
     * <p>First attempts to encode the full digest.  If the compressed result fits
     * in the MTU it is returned as-is.  Otherwise entries are batched using
     * <em>uncompressed</em> binary-size estimates (conservative: real wire packets
     * will be smaller due to LZ4 compression of repetitive content).
     */
    List<byte[]> serializeWithMtuEnforcement(GossipDigestDTO digest) throws IOException {
        byte[] fullPayload = codec.encode(digest);
        if (fullPayload.length <= UDP_MTU) {
            return List.of(fullPayload);
        }

        List<GossipNodeEntry> entries = digest.getEntries();
        if (entries.size() <= 1) {
            log.warn("Single gossip entry ({} bytes compressed) exceeds MTU; sending oversized",
                    fullPayload.length);
            return List.of(fullPayload);
        }

        // Split using uncompressed binary size estimates
        int envelopeBytes = codec.estimateEnvelopeSize(digest.getSenderNodeId());
        List<byte[]>       packets       = new ArrayList<>();
        List<GossipNodeEntry> batch      = new ArrayList<>();
        int batchEstimate = envelopeBytes;

        for (GossipNodeEntry entry : entries) {
            int entryBytes = codec.estimateEntrySize(entry);
            if (batchEstimate + entryBytes > UDP_MTU && !batch.isEmpty()) {
                packets.add(encodeChildDigest(digest, batch));
                batch.clear();
                batchEstimate = envelopeBytes;
            }
            batch.add(entry);
            batchEstimate += entryBytes;
        }
        if (!batch.isEmpty()) {
            packets.add(encodeChildDigest(digest, batch));
        }

        log.debug("Split {} entries into {} MTU-safe packets", entries.size(), packets.size());
        return packets;
    }

    private byte[] encodeChildDigest(GossipDigestDTO parent, List<GossipNodeEntry> batch)
            throws IOException {
        return codec.encode(GossipDigestDTO.builder()
                .senderNodeId(parent.getSenderNodeId())
                .senderHeartbeat(parent.getSenderHeartbeat())
                .entries(List.copyOf(batch))
                .build());
    }

    // ── Network I/O ───────────────────────────────────────────────────────────

    private int sendPacketsToPeers(List<byte[]> packets, List<NodeInfo> peers) {
        int successCount = 0;
        for (NodeInfo peer : peers) {
            if (sendPacketsToPeer(packets, peer)) successCount++;
        }
        return successCount;
    }

    private boolean sendPacketsToPeer(List<byte[]> packets, NodeInfo peer) {
        try {
            InetAddress address = InetAddress.getByName(peer.getHost());
            int port = peer.getGossipPort();
            for (byte[] data : packets) {
                datagramSocket.send(new DatagramPacket(data, data.length, address, port));
            }
            log.debug("Gossip sent to {} ({}:{}) [{} packet(s)]",
                    peer.getCacheNodeId(), peer.getHost(), port, packets.size());
            return true;
        } catch (IOException e) {
            log.warn("Failed to send gossip to {}: {}", peer.getCacheNodeId(), e.getMessage());
            return false;
        }
    }

    // ── Dirty-entry recovery ──────────────────────────────────────────────────

    private void restoreDirtyEntries(List<GossipNodeEntry> entries) {
        for (GossipNodeEntry entry : entries) {
            membershipList.markDirty(entry.getCacheNodeId());
        }
    }
}
