package com.edgefabric.caching.gossip;

import com.edgefabric.caching.config.GossipProperties;
import com.edgefabric.caching.dto.GossipDigestDTO;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GossipSenderTest {

    @Mock  private MembershipList   membershipList;
    @Mock  private DatagramSocket   datagramSocket;
    @Spy   private GossipMessageCodec codec;

    private GossipProperties gossipProperties;
    private GossipSender     gossipSender;

    private NodeInfo changedNode;

    @BeforeEach
    void setUp() {
        gossipProperties = new GossipProperties();
        gossipProperties.setFanout(2);

        gossipSender = new GossipSender(membershipList, gossipProperties, datagramSocket, codec);

        NodeInfo self    = NodeInfo.getInstance("self-node",    "127.0.0.1", 8082, 7946, Status.ALIVE,   10, 1);
        NodeInfo peerOne = NodeInfo.getInstance("peer-1",       "127.0.0.1", 8082, 7947, Status.ALIVE,    5, 1);
        NodeInfo peerTwo = NodeInfo.getInstance("peer-2",       "127.0.0.2", 8082, 7948, Status.ALIVE,    6, 1);
        // heartbeat=7 < FULL_ENTRY_HEARTBEAT_THRESHOLD(10) → will be a FULL entry
        changedNode = NodeInfo.getInstance("changed-node", "127.0.0.3", 8082, 7949, Status.SUSPECT,  7, 2);

        lenient().when(membershipList.getSelf()).thenReturn(self);
        lenient().when(membershipList.getRandomPeers(gossipProperties.getFanout()))
                 .thenReturn(List.of(peerOne, peerTwo));
    }

    @Test
    void shouldSendDirtyDigestToAllPeers() throws Exception {
        when(membershipList.getDirtyDigestAndClear()).thenReturn(List.of(changedNode));

        ArgumentCaptor<DatagramPacket> packetCaptor = ArgumentCaptor.forClass(DatagramPacket.class);
        doNothing().when(datagramSocket).send(packetCaptor.capture());

        gossipSender.sendGossip();

        verify(membershipList).bumpSelfHeartbeat();
        verify(membershipList).getDirtyDigestAndClear();
        verify(datagramSocket, times(2)).send(any(DatagramPacket.class));

        // Decode the captured packet with the same codec and verify its contents
        DatagramPacket packet = packetCaptor.getAllValues().getFirst();
        byte[] data = java.util.Arrays.copyOfRange(packet.getData(), 0, packet.getLength());
        GossipDigestDTO digest = codec.decode(data);

        assertEquals("self-node", digest.getSenderNodeId());
        assertEquals(1, digest.getEntries().size());

        GossipDigestDTO.GossipNodeEntry entry = digest.getEntries().getFirst();
        assertEquals("changed-node", entry.getCacheNodeId());
        assertEquals("SUSPECT",      entry.getStatus());
        assertEquals(7L,             entry.getHeartbeat());   // delta restored correctly
        assertTrue(entry.isFullEntry(), "heartbeat=7 < threshold → should be a FULL entry");
        assertEquals("127.0.0.3",    entry.getHost());
    }

    @Test
    void shouldSkipRoundWhenNoPeersAvailable() throws Exception {
        when(membershipList.getRandomPeers(gossipProperties.getFanout())).thenReturn(List.of());

        gossipSender.sendGossip();

        verify(membershipList).bumpSelfHeartbeat();
        verify(membershipList, never()).getDirtyDigestAndClear();
        verify(datagramSocket, never()).send(any());
    }

    @Test
    void shouldRestoreDirtyEntriesWhenEncodingFails() throws Exception {
        when(membershipList.getDirtyDigestAndClear()).thenReturn(List.of(changedNode));
        doThrow(new java.io.IOException("boom")).when(codec).encode(any(GossipDigestDTO.class));

        gossipSender.sendGossip();

        verify(membershipList).markDirty("changed-node");
        verify(datagramSocket, never()).send(any());
    }

    @Test
    void shouldRestoreDirtyEntriesWhenAllPeerSendsFail() throws Exception {
        when(membershipList.getDirtyDigestAndClear()).thenReturn(List.of(changedNode));
        doThrow(new java.io.IOException("send failed")).when(datagramSocket).send(any(DatagramPacket.class));

        gossipSender.sendGossip();

        verify(membershipList).markDirty("changed-node");
    }

    @Test
    void shouldSkipRoundWhenNoDirtyEntries() throws Exception {
        when(membershipList.getDirtyDigestAndClear()).thenReturn(List.of());

        gossipSender.sendGossip();

        verify(membershipList).bumpSelfHeartbeat();
        verify(membershipList).getDirtyDigestAndClear();
        verify(datagramSocket, never()).send(any());
    }

    @Test
    void shouldSplitLargeDigestIntoMultiplePackets() throws Exception {
        // 60 entries with long random IDs ensure the LZ4-compressed payload
        // exceeds the 1400-byte MTU and forces packet splitting.
        List<NodeInfo> manyNodes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            // ~77 chars of random content per ID — truly incompressible
            String uniqueId = "node-" + java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID();
            manyNodes.add(NodeInfo.getInstance(
                    uniqueId,
                    "192.168." + (i % 256) + ".1", 8082, 7946, Status.ALIVE, i, 0));
        }
        when(membershipList.getDirtyDigestAndClear()).thenReturn(manyNodes);
        doNothing().when(datagramSocket).send(any(DatagramPacket.class));

        gossipSender.sendGossip();

        // With 2 peers and chunked packets there must be more than 2 sends total
        verify(datagramSocket, atLeast(3)).send(any(DatagramPacket.class));
    }

    @Test
    void shouldNotRestoreDirtyEntriesWhenAtLeastOnePeerSendSucceeds() throws Exception {
        when(membershipList.getDirtyDigestAndClear()).thenReturn(List.of(changedNode));

        doAnswer(invocation -> {
            DatagramPacket packet = invocation.getArgument(0);
            String host = packet.getAddress().getHostAddress();
            if ("127.0.0.2".equals(host)) {
                throw new java.io.IOException("send failed");
            }
            return null;
        }).when(datagramSocket).send(any(DatagramPacket.class));

        gossipSender.sendGossip();

        verify(membershipList, never()).markDirty("changed-node");
    }

    /** Nodes with heartbeat >= threshold should be gossiped as DELTA entries (no host). */
    @Test
    void highHeartbeatNodeSentAsDeltaEntry() throws Exception {
        NodeInfo established = NodeInfo.getInstance("stable-node", "10.0.0.1", 8082, 7946,
                Status.ALIVE, GossipMessageCodec.FULL_ENTRY_HEARTBEAT_THRESHOLD, 1);
        when(membershipList.getDirtyDigestAndClear()).thenReturn(List.of(established));

        ArgumentCaptor<DatagramPacket> captor = ArgumentCaptor.forClass(DatagramPacket.class);
        doNothing().when(datagramSocket).send(captor.capture());

        gossipSender.sendGossip();

        DatagramPacket packet = captor.getAllValues().getFirst();
        GossipDigestDTO digest = codec.decode(
                java.util.Arrays.copyOfRange(packet.getData(), 0, packet.getLength()));

        GossipDigestDTO.GossipNodeEntry entry = digest.getEntries().getFirst();
        assertFalse(entry.isFullEntry(), "heartbeat >= threshold → should be a DELTA entry");
        assertNull(entry.getHost(), "DELTA entry must not carry host");
    }
}
