    package com.edgefabric.loadbalancer.service;

    import com.edgefabric.loadbalancer.model.CacheNode;
    import org.junit.jupiter.api.BeforeEach;
    import org.junit.jupiter.api.Test;

    import java.util.List;

    import com.edgefabric.hashing.core.ConsistentHashRing;
    import com.edgefabric.hashing.core.MurmurHashProvider;
    import static org.junit.jupiter.api.Assertions.*;

    class CacheRouterTest {

        private CacheRouter router;
        private ConsistentHashRing<CacheNode> ring;

        @BeforeEach
        void setup() {
            ring = new ConsistentHashRing<>(new MurmurHashProvider(), 100);

            ring.addNode(new CacheNode("node-1", "localhost", 8081));
            ring.addNode(new CacheNode("node-2", "localhost", 8082));

            router = new CacheRouter(ring);
        }

        @Test
        void routeShouldReturnCacheNode() {
            CacheNode node = router.route("user:123");
            assertNotNull(node);
        }

        @Test
        void sameKeyShouldRouteToSameNode() {
            CacheNode n1 = router.route("session:1");
            CacheNode n2 = router.route("session:1");

            assertEquals(n1, n2);
        }

        @Test
        void routeToReplicasShouldReturnRequestedCount() {
            List<CacheNode> replicas = router.routeToReplicas("user:42", 2);
            assertEquals(2, replicas.size());
            assertNotEquals(replicas.get(0), replicas.get(1));
        }

        @Test
        void routeToReplicasShouldCapAtAvailableNodes() {
            List<CacheNode> replicas = router.routeToReplicas("user:42", 10);
            assertEquals(2, replicas.size());
        }

        @Test
        void addNodeShouldMakeItRoutable() {
            CacheNode newNode = new CacheNode("node-3", "localhost", 8083);
            router.addNode(newNode);

            // With 3 nodes, routeToReplicas(key, 3) should return 3
            List<CacheNode> replicas = router.routeToReplicas("test-key", 3);
            assertEquals(3, replicas.size());
        }

        @Test
        void removeNodeShouldExcludeItFromRouting() {
            CacheNode nodeToRemove = new CacheNode("node-2", "localhost", 8082);
            router.removeNode(nodeToRemove);

            // Only node-1 remains
            List<CacheNode> replicas = router.routeToReplicas("any-key", 5);
            assertEquals(1, replicas.size());
            assertEquals("node-1", replicas.get(0).getNodeId());
        }

        @Test
        void getNodeById_returnsNodeWhenFound() {
            CacheNode node = router.getNodeById("node-1");
            assertNotNull(node);
            assertEquals("node-1", node.getNodeId());
            assertEquals("localhost", node.getHost());
            assertEquals(8081, node.getPort());
        }

        @Test
        void getNodeById_returnsNullWhenNotFound() {
            CacheNode node = router.getNodeById("non-existent-node");
            assertNull(node);
        }

        @Test
        void getNodeById_findsNodeAfterAddition() {
            CacheNode newNode = new CacheNode("node-3", "10.0.0.3", 8083);
            router.addNode(newNode);

            CacheNode found = router.getNodeById("node-3");
            assertNotNull(found);
            assertEquals("node-3", found.getNodeId());
            assertEquals("10.0.0.3", found.getHost());
        }

        @Test
        void getNodeById_returnsNullAfterRemoval() {
            CacheNode nodeToRemove = new CacheNode("node-2", "localhost", 8082);
            router.removeNode(nodeToRemove);

            CacheNode found = router.getNodeById("node-2");
            assertNull(found);
        }
    }
