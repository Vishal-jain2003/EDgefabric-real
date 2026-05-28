package com.edgefabric.caching.event;

import com.edgefabric.caching.model.NodeInfo;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopologyChangedEventTest {

    private NodeInfo createNode(String id, String host) {
        return new NodeInfo(id, host, 8080, 7946);
    }

    @Test
    void shouldExposeAddedNodes() {
        NodeInfo added = createNode("node-1", "10.0.0.1");
        TopologyChangedEvent event = new TopologyChangedEvent(
                this, Set.of(added), Collections.emptySet(), List.of(added));

        assertThat(event.getAddedNodes()).containsExactly(added);
    }

    @Test
    void shouldExposeRemovedNodes() {
        NodeInfo removed = createNode("node-2", "10.0.0.2");
        TopologyChangedEvent event = new TopologyChangedEvent(
                this, Collections.emptySet(), Set.of(removed), Collections.emptyList());

        assertThat(event.getRemovedNodes()).containsExactly(removed);
    }

    @Test
    void shouldExposeAllAliveNodes() {
        NodeInfo n1 = createNode("node-1", "10.0.0.1");
        NodeInfo n2 = createNode("node-2", "10.0.0.2");
        TopologyChangedEvent event = new TopologyChangedEvent(
                this, Set.of(n1), Collections.emptySet(), List.of(n1, n2));

        assertThat(event.getAllAliveNodes()).containsExactly(n1, n2);
    }

    @Test
    void shouldReturnUnmodifiableAddedNodes() {
        Set<NodeInfo> added = new HashSet<>();
        added.add(createNode("node-1", "10.0.0.1"));
        TopologyChangedEvent event = new TopologyChangedEvent(
                this, added, Collections.emptySet(), Collections.emptyList());

        Set<NodeInfo> addedNodes = event.getAddedNodes();
        NodeInfo hackNode1 = createNode("hack", "evil");
        assertThatThrownBy(() -> addedNodes.add(hackNode1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReturnUnmodifiableRemovedNodes() {
        Set<NodeInfo> removed = new HashSet<>();
        removed.add(createNode("node-2", "10.0.0.2"));
        TopologyChangedEvent event = new TopologyChangedEvent(
                this, Collections.emptySet(), removed, Collections.emptyList());

        Set<NodeInfo> removedNodes = event.getRemovedNodes();
        NodeInfo hackNode2 = createNode("hack", "evil");
        assertThatThrownBy(() -> removedNodes.add(hackNode2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReturnUnmodifiableAllAliveNodes() {
        NodeInfo n1 = createNode("node-1", "10.0.0.1");
        TopologyChangedEvent event = new TopologyChangedEvent(
                this, Collections.emptySet(), Collections.emptySet(), List.of(n1));

        List<NodeInfo> aliveNodes = event.getAllAliveNodes();
        NodeInfo hackNode3 = createNode("hack", "evil");
        assertThatThrownBy(() -> aliveNodes.add(hackNode3))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldPreserveSourceObject() {
        Object source = new Object();
        TopologyChangedEvent event = new TopologyChangedEvent(
                source, Collections.emptySet(), Collections.emptySet(), Collections.emptyList());

        assertThat(event.getSource()).isSameAs(source);
    }

    @Test
    void shouldHandleEmptySets() {
        TopologyChangedEvent event = new TopologyChangedEvent(
                this, Collections.emptySet(), Collections.emptySet(), Collections.emptyList());

        assertThat(event.getAddedNodes()).isEmpty();
        assertThat(event.getRemovedNodes()).isEmpty();
        assertThat(event.getAllAliveNodes()).isEmpty();
    }

    @Test
    void shouldPreventDirectModificationOfAddedNodes() {
        Set<NodeInfo> added = new HashSet<>();
        NodeInfo n1 = createNode("node-1", "10.0.0.1");
        added.add(n1);

        TopologyChangedEvent event = new TopologyChangedEvent(
                this, added, Collections.emptySet(), Collections.emptyList());

        // The returned set should be unmodifiable (cannot add through the getter)
        Set<NodeInfo> returnedNodes = event.getAddedNodes();
        NodeInfo hackNode4 = createNode("node-2", "10.0.0.2");
        assertThatThrownBy(() -> returnedNodes.add(hackNode4))
                .isInstanceOf(UnsupportedOperationException.class);

        // The original content is preserved
        assertThat(event.getAddedNodes()).contains(n1);
    }

    @Test
    void shouldHandleMultipleNodesInEachSet() {
        NodeInfo added1 = createNode("a1", "10.0.0.1");
        NodeInfo added2 = createNode("a2", "10.0.0.2");
        NodeInfo removed1 = createNode("r1", "10.0.0.3");
        NodeInfo alive1 = createNode("a1", "10.0.0.1");
        NodeInfo alive2 = createNode("a2", "10.0.0.2");

        TopologyChangedEvent event = new TopologyChangedEvent(
                this, Set.of(added1, added2), Set.of(removed1), List.of(alive1, alive2));

        assertThat(event.getAddedNodes()).hasSize(2);
        assertThat(event.getRemovedNodes()).hasSize(1);
        assertThat(event.getAllAliveNodes()).hasSize(2);
    }
}
