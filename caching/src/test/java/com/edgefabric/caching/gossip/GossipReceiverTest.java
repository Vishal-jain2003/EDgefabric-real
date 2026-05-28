package com.edgefabric.caching.gossip;

import com.edgefabric.caching.dto.GossipDigestDTO;
import com.edgefabric.caching.dto.GossipDigestDTO.GossipNodeEntry;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GossipReceiverTest {

    @Mock private MembershipList    membershipList;
    @Mock private Tracer            tracer;
    @Mock private Span              span;
    @Mock private Tracer.SpanInScope spanInScope;

    private DatagramSocket   receiverSocket;
    private DatagramSocket   senderSocket;
    private GossipReceiver   gossipReceiver;
    private GossipMessageCodec codec;

    @BeforeEach
    void setUp() throws Exception {
        receiverSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        senderSocket   = new DatagramSocket();
        codec          = new GossipMessageCodec();

        lenient().when(tracer.nextSpan()).thenReturn(span);
        lenient().when(span.name(anyString())).thenReturn(span);
        lenient().when(span.start()).thenReturn(span);
        lenient().when(span.tag(anyString(), anyString())).thenReturn(span);
        lenient().when(tracer.withSpan(span)).thenReturn(spanInScope);

        gossipReceiver = new GossipReceiver(receiverSocket, codec, membershipList, tracer);
        gossipReceiver.start();
    }

    @AfterEach
    void tearDown() {
        gossipReceiver.stop();
        if (!senderSocket.isClosed()) senderSocket.close();
    }

    @Test
    void shouldMergeAllEntriesFromValidPacket() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        doAnswer(inv -> { latch.countDown(); return null; })
                .when(membershipList).merge(any(NodeInfo.class));

        sendDigest(GossipDigestDTO.builder()
                .senderNodeId("sender-node")
                .entries(List.of(
                        GossipNodeEntry.fromNodeInfo(NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.ALIVE,   10, 1)),
                        GossipNodeEntry.fromNodeInfo(NodeInfo.getInstance("node-2", "127.0.0.2", 8082, 7947, Status.SUSPECT, 11, 2))
                ))
                .build());

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        verify(membershipList, timeout(2000).times(2)).merge(any(NodeInfo.class));
    }

    @Test
    void shouldIgnoreEmptyDigest() throws Exception {
        sendDigest(GossipDigestDTO.builder()
                .senderNodeId("sender-node")
                .entries(List.of())
                .build());

        verify(membershipList, after(300).never()).merge(any(NodeInfo.class));
    }

    @Test
    void shouldContinueAfterMalformedPacketAndProcessNextValidPacket() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> { latch.countDown(); return null; })
                .when(membershipList).merge(any(NodeInfo.class));

        // Malformed bytes — magic check will reject this fast without OOM
        sendBytes("not-binary-gossip".getBytes());

        sendDigest(GossipDigestDTO.builder()
                .senderNodeId("sender-node")
                .entries(List.of(
                        GossipNodeEntry.fromNodeInfo(NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.ALIVE, 10, 1))
                ))
                .build());

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        verify(membershipList, timeout(2000).times(1)).merge(any(NodeInfo.class));
    }

    @Test
    void shouldIgnoreNullAndEntrylessDigests() throws Exception {
        invokeProcessDigest(null);
        invokeProcessDigest(GossipDigestDTO.builder().senderNodeId("x").entries(null).build());
        invokeProcessDigest(GossipDigestDTO.builder().senderNodeId("x").entries(List.of()).build());

        verify(membershipList, never()).merge(any(NodeInfo.class));
    }

    @Test
    void shouldContinueWhenEntryConversionFails() throws Exception {
        // A FULL entry with an invalid status name will throw inside mergeEntry
        GossipNodeEntry invalidEntry = GossipNodeEntry.builder()
                .cacheNodeId("broken-node")
                .host("127.0.0.5")      // non-null → FULL
                .servicePort(8082).gossipPort(7946)
                .status("NOT_A_STATUS")
                .heartbeat(1).incarnation(1)
                .build();

        GossipNodeEntry validEntry = GossipNodeEntry.fromNodeInfo(
                NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.ALIVE, 10, 1));

        invokeProcessDigest(GossipDigestDTO.builder()
                .senderNodeId("sender-node")
                .entries(List.of(invalidEntry, validEntry))
                .build());

        verify(membershipList, times(1)).merge(any(NodeInfo.class));
    }

    @Test
    void shouldContinueWhenMembershipMergeThrowsForOneEntry() throws Exception {
        doThrow(new RuntimeException("merge failed")).doNothing()
                .when(membershipList).merge(any(NodeInfo.class));

        invokeProcessDigest(GossipDigestDTO.builder()
                .senderNodeId("sender-node")
                .entries(List.of(
                        GossipNodeEntry.fromNodeInfo(NodeInfo.getInstance("node-1", "127.0.0.1", 8082, 7946, Status.ALIVE,   10, 1)),
                        GossipNodeEntry.fromNodeInfo(NodeInfo.getInstance("node-2", "127.0.0.2", 8082, 7947, Status.SUSPECT, 11, 2))
                ))
                .build());

        verify(membershipList, times(2)).merge(any(NodeInfo.class));
    }

    /**
     * A DELTA entry for a node that is already in the membership list must be
     * merged using the existing node's network coordinates.
     */
    @Test
    void deltaEntryMergedUsingExistingNodeCoordinates() throws Exception {
        NodeInfo existing = NodeInfo.getInstance("known-node", "10.0.1.1", 8082, 7946, Status.ALIVE, 5, 0);
        when(membershipList.getNode("known-node")).thenReturn(existing);

        // DELTA entry: host=null, only state is sent
        GossipNodeEntry deltaEntry = GossipNodeEntry.fromNodeInfoDelta(
                NodeInfo.getInstance("known-node", "10.0.1.1", 8082, 7946, Status.SUSPECT, 50, 1));

        invokeProcessDigest(GossipDigestDTO.builder()
                .senderNodeId("peer")
                .entries(List.of(deltaEntry))
                .build());

        verify(membershipList, times(1)).merge(any(NodeInfo.class));
        verify(membershipList).getNode("known-node");
    }

    /**
     * A DELTA entry for a node that is NOT yet in the membership list must be
     * silently skipped — the receiver will get a FULL entry from another peer.
     */
    @Test
    void deltaEntryForUnknownNodeIsSkipped() throws Exception {
        when(membershipList.getNode("new-node")).thenReturn(null);

        GossipNodeEntry deltaEntry = GossipNodeEntry.fromNodeInfoDelta(
                NodeInfo.getInstance("new-node", "10.0.99.1", 8082, 7946, Status.ALIVE, 3, 0));

        invokeProcessDigest(GossipDigestDTO.builder()
                .senderNodeId("peer")
                .entries(List.of(deltaEntry))
                .build());

        verify(membershipList, never()).merge(any(NodeInfo.class));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void sendDigest(GossipDigestDTO digest) throws Exception {
        sendBytes(codec.encode(digest));
    }

    private void sendBytes(byte[] data) throws Exception {
        DatagramPacket packet = new DatagramPacket(
                data, data.length,
                InetAddress.getByName("127.0.0.1"),
                receiverSocket.getLocalPort());
        senderSocket.send(packet);
    }

    private void invokeProcessDigest(GossipDigestDTO digest) throws Exception {
        Method method = GossipReceiver.class.getDeclaredMethod("processDigest", GossipDigestDTO.class);
        method.setAccessible(true);
        method.invoke(gossipReceiver, digest);
    }
}
